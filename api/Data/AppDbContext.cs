using System.Text.RegularExpressions;
using api.Models;
using Microsoft.EntityFrameworkCore;

namespace api.Data;

/// <summary>
/// The backend's PostgreSQL model. It mirrors the Room entities in
/// client/app/src/main/java/com/example/client/data/local/entity/ so mobile and backend share one shape.
///
/// Parity rules (enforced by the api.Tests "RoomParityTests"):
///   * Table names are identical to Room's (cbos, cbo_collections, product_lines,
///     foodspace_beneficiary_records, vetting_decisions).
///   * A C# property is the Room field name in PascalCase (cboId -> CboId); the column is snake_case (cbo_id).
///     JSON on the wire stays camelCase, i.e. the Room field names.
///   * Kotlin nullability = C# nullability. Enums are stored as their UPPER_CASE names, like Room does.
///   * Ids and epoch-millisecond timestamps are stored as sent (text / bigint), so nothing is converted or lost.
///
/// Deliberate differences from Room:
///   * Server-only columns (no Room equivalent): received_at, sync_attempts, last_sync_attempt_at,
///     sync_error, forwarding_status and next_forward_attempt_at (Foodspace forwarding, see ForwardingStatus)
///     and submitted_by on collections/decisions (Admin monitoring, #49-#51), and fetched_at on the Foodspace
///     cache. SyncStatus means "reached the backend" on both sides; forwarding_status tracks Foodspace.
///   * List fields are native Postgres arrays (text[] / boolean[]) instead of delimiter-joined text.
///   * product_lines.collection_id is a real foreign key (cascade delete). Room only relates them "in spirit".
///   * vetting_decisions.foodspace_record_id and cbo_collections.cbo_id are intentionally NOT foreign keys:
///     the beneficiary cache is replaced on every fetch, and a collection must never be rejected because the
///     CBO list hasn't been pulled yet.
///   * Not modelled: Room's "sync_payloads" (a demo table of the current queue prototype).
///   * "users" is server-only (login accounts); the device holds a JWT, not a user record.
/// </summary>
public class AppDbContext : DbContext
{
    public AppDbContext(DbContextOptions<AppDbContext> options) : base(options)
    {
    }

    public DbSet<Cbo> Cbos => Set<Cbo>();
    public DbSet<CboCollection> CboCollections => Set<CboCollection>();
    public DbSet<ProductLine> ProductLines => Set<ProductLine>();

    /// <summary>Read-only cache of Foodspace beneficiary records (fetch-and-replace).</summary>
    public DbSet<FoodspaceBeneficiaryRecord> FoodspaceBeneficiaryRecords => Set<FoodspaceBeneficiaryRecord>();

    public DbSet<VettingDecision> VettingDecisions => Set<VettingDecision>();

    /// <summary>Server-owned login accounts. No Room counterpart (see AppUser).</summary>
    public DbSet<AppUser> Users => Set<AppUser>();

    protected override void ConfigureConventions(ModelConfigurationBuilder configurationBuilder)
    {
        configurationBuilder.Properties<SyncStatus>().HaveConversion<UpperCaseEnumConverter<SyncStatus>>();
        configurationBuilder.Properties<ForwardingStatus>().HaveConversion<UpperSnakeEnumConverter<ForwardingStatus>>();
        configurationBuilder.Properties<DecisionOutcome>().HaveConversion<UpperCaseEnumConverter<DecisionOutcome>>();
        configurationBuilder.Properties<Tone>().HaveConversion<UpperCaseEnumConverter<Tone>>();
        // UserRole names are already the wire values (CBO_COLLECTION, ...), so plain string conversion is exact.
        configurationBuilder.Properties<UserRole>().HaveConversion<string>();
    }

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<Cbo>(e =>
        {
            e.ToTable("cbos");
            e.HasKey(x => x.Id);
        });

        modelBuilder.Entity<CboCollection>(e =>
        {
            e.ToTable("cbo_collections");
            e.HasKey(x => x.Id);
            e.Property(x => x.ReceivedAt).HasDefaultValueSql("now()");
            e.HasIndex(x => x.CboId).HasDatabaseName("ix_cbo_collections_cbo_id");
            e.HasIndex(x => x.SyncStatus).HasDatabaseName("ix_cbo_collections_sync_status");
            e.HasIndex(x => x.ForwardingStatus).HasDatabaseName("ix_cbo_collections_forwarding_status");
            // The database-level duplicate rule (#38): at most one ORIGINAL record per real-world collection.
            // Suspected duplicates (duplicate_of_id set) are stored for review and are exempt.
            e.HasIndex(x => x.DuplicateKey).IsUnique().HasFilter("duplicate_of_id IS NULL")
                .HasDatabaseName("ux_cbo_collections_duplicate_key_original");
            e.HasIndex(x => x.DuplicateOfId).HasDatabaseName("ix_cbo_collections_duplicate_of_id");
        });

        modelBuilder.Entity<ProductLine>(e =>
        {
            e.ToTable("product_lines");
            e.HasKey(x => x.Id);
            e.HasOne(x => x.Collection)
                .WithMany(c => c.ProductLines)
                .HasForeignKey(x => x.CollectionId)
                .OnDelete(DeleteBehavior.Cascade);
            e.HasIndex(x => x.CollectionId).HasDatabaseName("ix_product_lines_collection_id");
        });

        modelBuilder.Entity<FoodspaceBeneficiaryRecord>(e =>
        {
            e.ToTable("foodspace_beneficiary_records");
            e.HasKey(x => x.Id);
            e.Property(x => x.FetchedAt).HasDefaultValueSql("now()");
        });

        modelBuilder.Entity<VettingDecision>(e =>
        {
            e.ToTable("vetting_decisions");
            e.HasKey(x => x.Id);
            e.Property(x => x.ReceivedAt).HasDefaultValueSql("now()");
            e.HasIndex(x => x.FoodspaceRecordId).HasDatabaseName("ix_vetting_decisions_foodspace_record_id");
            e.HasIndex(x => x.SyncStatus).HasDatabaseName("ix_vetting_decisions_sync_status");
            e.HasIndex(x => x.OfficerId).HasDatabaseName("ix_vetting_decisions_officer_id");
        });

        modelBuilder.Entity<AppUser>(e =>
        {
            e.ToTable("users");
            e.HasKey(x => x.Id);
            e.Property(x => x.CreatedAt).HasDefaultValueSql("now()");
            e.HasIndex(x => x.Username).IsUnique().HasDatabaseName("ux_users_username");
        });

        ApplySnakeCaseNames(modelBuilder);
    }

    // PascalCase property -> snake_case column (LegalName -> legal_name, CboId -> cbo_id).
    private static void ApplySnakeCaseNames(ModelBuilder modelBuilder)
    {
        foreach (var entity in modelBuilder.Model.GetEntityTypes())
        {
            var table = entity.GetTableName()!;

            foreach (var property in entity.GetProperties())
            {
                property.SetColumnName(ToSnakeCase(property.Name));
            }

            foreach (var key in entity.GetKeys())
            {
                key.SetName($"pk_{table}");
            }

            foreach (var foreignKey in entity.GetForeignKeys())
            {
                var principal = foreignKey.PrincipalEntityType.GetTableName();
                var columns = string.Join("_", foreignKey.Properties.Select(p => ToSnakeCase(p.Name)));
                foreignKey.SetConstraintName($"fk_{table}_{principal}_{columns}");
            }
        }
    }

    private static string ToSnakeCase(string name) =>
        Regex.Replace(name, "([a-z0-9])([A-Z])", "$1_$2").ToLowerInvariant();
}
