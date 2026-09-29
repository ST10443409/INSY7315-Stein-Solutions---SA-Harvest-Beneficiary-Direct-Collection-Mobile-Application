using System.Text.RegularExpressions;
using api.Data;
using api.Models;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata;

namespace api.Tests;

/// <summary>
/// Guards against field-name drift between the Android Room entities and the backend EF model.
/// It reads the Kotlin sources directly, so changing either side without the other fails the build.
/// </summary>
public class RoomParityTests
{
    // Columns that exist only on the server (see the XML docs on AppDbContext).
    private static readonly HashSet<string> ServerOnlyProperties = new()
    {
        nameof(ForwardedEntity.ReceivedAt),
        nameof(ForwardedEntity.SyncAttempts),
        nameof(ForwardedEntity.LastSyncAttemptAt),
        nameof(ForwardedEntity.SyncError),
        nameof(CboCollection.SubmittedBy),
        nameof(FoodspaceBeneficiaryRecord.FetchedAt),
    };

    // Room tables deliberately not modelled on the server (demo table of the old queue prototype).
    private static readonly HashSet<string> IgnoredRoomTables = new() { "sync_payloads" };

    private record KotlinField(string Name, string Type, bool Nullable);

    private record KotlinEntity(string Table, string ClassName, List<KotlinField> Fields);

    private static IModel BuildModel()
    {
        var options = new DbContextOptionsBuilder<AppDbContext>()
            .UseNpgsql("Host=localhost;Database=parity;Username=x;Password=x") // never connected to
            .Options;
        using var context = new AppDbContext(options);
        return context.Model;
    }

    private static string RoomRoot()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null)
        {
            var candidate = Path.Combine(dir.FullName, "client", "app", "src", "main", "java",
                "com", "example", "client", "data");
            if (Directory.Exists(candidate)) return candidate;
            dir = dir.Parent;
        }
        throw new DirectoryNotFoundException("Could not locate the Android 'client' project above the test binaries.");
    }

    private static List<KotlinEntity> ReadRoomEntities()
    {
        var files = Directory.GetFiles(RoomRoot(), "*.kt", SearchOption.AllDirectories);
        var entities = new List<KotlinEntity>();
        foreach (var file in files)
        {
            var text = File.ReadAllText(file);
            var match = Regex.Match(text,
                @"@Entity\(tableName\s*=\s*""(?<table>\w+)""\)\s*data class (?<name>\w+)\((?<body>.*?)\n\)",
                RegexOptions.Singleline);
            if (!match.Success) continue;

            var body = Regex.Replace(match.Groups["body"].Value, @"//.*", ""); // drop comments
            var fields = Regex.Matches(body, @"val\s+(?<n>\w+)\s*:\s*(?<t>[\w<>]+)(?<q>\?)?")
                .Select(m => new KotlinField(m.Groups["n"].Value, m.Groups["t"].Value, m.Groups["q"].Success))
                .ToList();
            entities.Add(new KotlinEntity(match.Groups["table"].Value, match.Groups["name"].Value, fields));
        }
        return entities;
    }

    private static string Pascal(string name) => char.ToUpperInvariant(name[0]) + name[1..];

    private static bool TypesMatch(string kotlinType, Type clrType)
    {
        var type = Nullable.GetUnderlyingType(clrType) ?? clrType;
        return kotlinType switch
        {
            "String" => type == typeof(string),
            "Boolean" => type == typeof(bool),
            "Int" => type == typeof(int),
            "Long" => type == typeof(long),
            "Double" => type == typeof(double),
            "List<String>" => type == typeof(List<string>),
            "List<Boolean>" => type == typeof(List<bool>),
            // Room enums (SyncStatus, Tone, DecisionOutcome): same name on the C# side.
            _ => type.IsEnum && type.Name == kotlinType,
        };
    }

    [Fact]
    public void RoomEntities_AreFound()
    {
        var entities = ReadRoomEntities();

        // If the parser silently found nothing, every other test would pass vacuously.
        Assert.True(entities.Count >= 6, $"Expected the Room entities to be parsed, found {entities.Count}.");
    }

    [Fact]
    public void EveryRoomTable_HasAMatchingBackendTable()
    {
        var model = BuildModel();
        var backendTables = model.GetEntityTypes().Select(e => e.GetTableName()).ToHashSet();

        foreach (var room in ReadRoomEntities().Where(r => !IgnoredRoomTables.Contains(r.Table)))
        {
            Assert.True(backendTables.Contains(room.Table),
                $"Room table '{room.Table}' ({room.ClassName}) has no backend table of the same name.");
        }
    }

    [Fact]
    public void EveryRoomField_ExistsOnTheBackend_WithTheSameTypeAndNullability()
    {
        var model = BuildModel();
        var problems = new List<string>();

        foreach (var room in ReadRoomEntities().Where(r => !IgnoredRoomTables.Contains(r.Table)))
        {
            var entity = model.GetEntityTypes().Single(e => e.GetTableName() == room.Table);
            foreach (var field in room.Fields)
            {
                var property = entity.FindProperty(Pascal(field.Name));
                if (property == null)
                {
                    problems.Add($"{room.Table}.{field.Name}: missing on the backend (expected {Pascal(field.Name)}).");
                    continue;
                }
                if (property.IsNullable != field.Nullable)
                    problems.Add($"{room.Table}.{field.Name}: nullability differs (Room {(field.Nullable ? "nullable" : "required")}, backend {(property.IsNullable ? "nullable" : "required")}).");
                if (!TypesMatch(field.Type, property.ClrType))
                    problems.Add($"{room.Table}.{field.Name}: type differs (Room {field.Type}, backend {property.ClrType.Name}).");
            }
        }

        Assert.True(problems.Count == 0, "Room/backend drift:\n" + string.Join("\n", problems));
    }

    [Fact]
    public void EveryBackendProperty_IsInRoom_OrDeclaredServerOnly()
    {
        var model = BuildModel();
        var rooms = ReadRoomEntities().ToDictionary(r => r.Table);
        var problems = new List<string>();

        foreach (var entity in model.GetEntityTypes())
        {
            var room = rooms[entity.GetTableName()!];
            var roomFields = room.Fields.Select(f => Pascal(f.Name)).ToHashSet();
            foreach (var property in entity.GetProperties())
            {
                if (!roomFields.Contains(property.Name) && !ServerOnlyProperties.Contains(property.Name))
                    problems.Add($"{entity.GetTableName()}.{property.Name}: not in Room and not declared server-only.");
            }
        }

        Assert.True(problems.Count == 0, "Undeclared backend columns:\n" + string.Join("\n", problems));
    }

    [Fact]
    public void Enums_HaveTheSameValuesAsTheKotlinEnums()
    {
        var kotlinDir = Path.Combine(RoomRoot(), "local", "entity");
        var pairs = new (string Kotlin, Type Clr)[]
        {
            ("SyncStatus", typeof(SyncStatus)),
            ("DecisionOutcome", typeof(DecisionOutcome)),
            ("Tone", typeof(Tone)),
        };

        foreach (var (kotlin, clr) in pairs)
        {
            var text = File.ReadAllText(Path.Combine(kotlinDir, kotlin + ".kt"));
            var kotlinValues = Regex.Match(text, @"enum class \w+ \{(?<v>[^}]*)\}").Groups["v"].Value
                .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
            var backendValues = Enum.GetNames(clr).Select(n => n.ToUpperInvariant());

            Assert.Equal(kotlinValues, backendValues);
        }
    }

    [Theory]
    [InlineData(SyncStatus.Pending, "PENDING")]
    [InlineData(SyncStatus.Failed, "FAILED")]
    public void EnumConverter_WritesTheUpperCaseNamesRoomStores(SyncStatus value, string expected)
    {
        var converter = new UpperCaseEnumConverter<SyncStatus>();

        Assert.Equal(expected, converter.ConvertToProvider(value));
        Assert.Equal(value, converter.ConvertFromProvider(expected));
    }
}
