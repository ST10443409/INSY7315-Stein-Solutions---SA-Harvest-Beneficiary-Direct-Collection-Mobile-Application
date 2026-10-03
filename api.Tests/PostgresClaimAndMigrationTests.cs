using api.Data;
using api.Models;
using api.Services;
using api.Services.Foodspace;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Npgsql;

namespace api.Tests;

/// <summary>
/// Runs a [Fact] only when TEST_POSTGRES is set (a connection string to the maintenance database of a PostgreSQL server the
/// tests may create databases on). Everything else in this project uses EF's in-memory provider, which cannot tell us whether
/// the migrations apply or whether a claim is really arbitrated by SQL; the CI workflow starts a PostgreSQL service container
/// and sets this, and locally:
///   docker run -d -e POSTGRES_HOST_AUTH_METHOD=trust -p 127.0.0.1:55432:5432 postgres:17-alpine
///   TEST_POSTGRES="Host=127.0.0.1;Port=55432;Username=postgres;Database=postgres" dotnet test api.Tests
/// </summary>
public sealed class PostgresFactAttribute : FactAttribute
{
    public const string Variable = "TEST_POSTGRES";

    public PostgresFactAttribute()
    {
        if (string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable(Variable)))
            Skip = $"Set {Variable} to a PostgreSQL connection string to run this test (see PostgresFactAttribute).";
    }
}

/// <summary>
/// The parts of the backend that only a real PostgreSQL can prove: that every migration applies to an empty database and
/// leaves the schema matching the model (so a model change without a migration, or a migration PostgreSQL rejects, fails CI
/// instead of failing in Azure), and that <see cref="ForwardingClaim"/> really lets exactly one of several workers send a record.
/// Each test gets its own throwaway database.
/// </summary>
public class PostgresClaimAndMigrationTests : IAsyncLifetime
{
    private readonly string? _admin = Environment.GetEnvironmentVariable(PostgresFactAttribute.Variable);
    private readonly string _database = "claim_test_" + Guid.NewGuid().ToString("N");
    private readonly ForwardingClaimTests.ManualTime _time = new();
    private readonly ForwardingClaimTests.CountingClient _client = new();
    private readonly IOptions<FoodspaceOptions> _options =
        Microsoft.Extensions.Options.Options.Create(new FoodspaceOptions { BaseUrl = "http://foodspace.test", ClaimLeaseSeconds = 120 });

    private string ConnectionString => new NpgsqlConnectionStringBuilder(_admin) { Database = _database, Pooling = true }.ConnectionString;

    private AppDbContext NewDb() => new(new DbContextOptionsBuilder<AppDbContext>().UseNpgsql(ConnectionString).Options);

    public async Task InitializeAsync()
    {
        if (_admin is null) return; // the tests are skipped
        await using (var admin = new NpgsqlConnection(_admin))
        {
            await admin.OpenAsync();
            await using var create = new NpgsqlCommand($"CREATE DATABASE \"{_database}\"", admin);
            await create.ExecuteNonQueryAsync();
        }

        await using var db = NewDb();
        await db.Database.MigrateAsync(); // EF throws here too if the model has changes no migration covers
    }

    public async Task DisposeAsync()
    {
        if (_admin is null) return;
        NpgsqlConnection.ClearAllPools();
        await using var admin = new NpgsqlConnection(_admin);
        await admin.OpenAsync();
        await using var drop = new NpgsqlCommand($"DROP DATABASE IF EXISTS \"{_database}\" WITH (FORCE)", admin);
        await drop.ExecuteNonQueryAsync();
    }

    private static CboCollection Collection(string id) => new()
    {
        Id = id, CboId = "cbo-1", ArrivalTime = "10:00", DonorName = "Donor " + id, DeliveryNote = "DN-" + id, CollectNotes = "",
    };

    // ── migrations ─────────────────────────────────────────────────────────────────

    [PostgresFact]
    public async Task EveryMigration_AppliesToAnEmptyDatabase_AndTheModelHasNoChangesWithoutOne()
    {
        await using var db = NewDb();

        Assert.Empty(await db.Database.GetPendingMigrationsAsync());
        Assert.NotEmpty(await db.Database.GetAppliedMigrationsAsync());
        Assert.False(db.Database.HasPendingModelChanges(), "The model changed without a migration: run `dotnet ef migrations add`.");
    }

    [PostgresFact]
    public async Task TheClaimColumns_ExistAndRoundTrip()
    {
        var until = new DateTimeOffset(2026, 10, 3, 9, 0, 0, TimeSpan.Zero);
        var claim = Guid.NewGuid();
        await using (var db = NewDb())
        {
            db.CboCollections.Add(Collection("c1"));
            db.VettingDecisions.Add(new VettingDecision { Id = "d1", FoodspaceRecordId = "r", OfficerId = "o", ForwardClaimId = claim, ForwardClaimedUntil = until });
            await db.SaveChangesAsync();
        }

        await using var check = NewDb();
        var decision = await check.VettingDecisions.SingleAsync();
        Assert.Equal(claim, decision.ForwardClaimId);
        Assert.Equal(until, decision.ForwardClaimedUntil);
        Assert.Null((await check.CboCollections.SingleAsync()).ForwardClaimId);
    }

    // ── the claim, arbitrated by real SQL ──────────────────────────────────────────

    [PostgresFact]
    public async Task TwoWorkersThatReadTheSameRow_ExactlyOneWinsTheClaim()
    {
        await using (var seed = NewDb()) { seed.CboCollections.Add(Collection("c1")); await seed.SaveChangesAsync(); }
        await using var workerA = NewDb();
        await using var workerB = NewDb();
        var seenByA = await workerA.CboCollections.SingleAsync();
        var seenByB = await workerB.CboCollections.SingleAsync();
        var lease = TimeSpan.FromSeconds(120);

        var a = await ForwardingClaim.TryClaimAsync(workerA, seenByA, _time.Now, lease, default);
        var b = await ForwardingClaim.TryClaimAsync(workerB, seenByB, _time.Now, lease, default);

        Assert.True(a);
        Assert.False(b);
    }

    [PostgresFact]
    public async Task AWorkerWithAStaleRead_CannotClaimAfterTheRecordWasSentAndReleased()
    {
        await using (var seed = NewDb()) { seed.CboCollections.Add(Collection("c1")); await seed.SaveChangesAsync(); }
        await using var workerA = NewDb();
        await using var workerB = NewDb();
        var seenByA = await workerA.CboCollections.SingleAsync();
        var seenByB = await workerB.CboCollections.SingleAsync();
        var lease = TimeSpan.FromSeconds(120);

        Assert.True(await ForwardingClaim.TryClaimAsync(workerA, seenByA, _time.Now, lease, default));
        Assert.True(await ForwardingClaim.ReleaseAsync(workerA, seenByA, default));

        Assert.False(await ForwardingClaim.TryClaimAsync(workerB, seenByB, _time.Now, lease, default));
    }

    [PostgresFact]
    public async Task FourForwardersRunningAtOnce_SendEveryCollectionExactlyOnce()
    {
        const int records = 20;
        await using (var seed = NewDb())
        {
            seed.CboCollections.AddRange(Enumerable.Range(1, records).Select(i => Collection($"c{i:D2}")));
            await seed.SaveChangesAsync();
        }
        _client.Delay = TimeSpan.FromMilliseconds(10);

        // Four "processes", each with its own connection to the one database, all forwarding at the same time.
        var attempted = await Task.WhenAll(Enumerable.Range(0, 4).Select(async _ =>
        {
            await using var db = NewDb();
            return await new CboCollectionForwarder(db, _client, _options, _time, NullLogger<CboCollectionForwarder>.Instance).ForwardDueAsync();
        }));

        Assert.Equal(records, _client.Sent);
        Assert.Equal(records, attempted.Sum());
        await using var check = NewDb();
        Assert.All(await check.CboCollections.ToListAsync(), c =>
        {
            Assert.Equal(ForwardingStatus.Forwarded, c.ForwardingStatus);
            Assert.Equal(1, c.SyncAttempts);
            Assert.Null(c.ForwardClaimedUntil);
        });
    }

    // ── accounts, on real SQL ──────────────────────────────────────────────────────
    // What the in-memory provider cannot show: a unique index really refusing a duplicate username when two Admins create the
    // same one at once, the search and ordering translating to SQL, and the audit trail round-tripping.

    private static UserManagementService Accounts(AppDbContext db) =>
        new(db, new Microsoft.AspNetCore.Identity.PasswordHasher<AppUser>(), TimeProvider.System, NullLogger<UserManagementService>.Instance);

    private static readonly AdminActor TestAdmin = new(Guid.NewGuid(), "pg.admin");

    private static api.DTOs.CreateUserRequest NewUser(string username, string role = "VETTING", string? cbo = null) =>
        new() { Username = username, Role = role, CboId = cbo, Password = "a-long-enough-passphrase-1" };

    [PostgresFact]
    public async Task TwoAdminsCreatingTheSameUsernameAtOnce_ExactlyOneWins_TheUniqueIndexDecides()
    {
        var results = await Task.WhenAll(Enumerable.Range(0, 4).Select(async _ =>
        {
            await using var db = NewDb();
            return await Accounts(db).CreateAsync(TestAdmin, NewUser("race.user"));
        }));

        Assert.Equal(1, results.Count(r => r.Status == UserResultStatus.Ok));
        Assert.Equal(3, results.Count(r => r.Status == UserResultStatus.Conflict)); // told "taken", not a server error
        await using var check = NewDb();
        Assert.Equal(1, await check.Users.CountAsync(u => u.Username == "race.user"));
        Assert.Equal(1, await check.UserAudit.CountAsync(a => a.TargetUsername == "race.user")); // and only one audit line
    }

    [PostgresFact]
    public async Task Accounts_CanBeCreatedListedSearchedChangedAndAudited()
    {
        await using var db = NewDb();
        var service = Accounts(db);
        foreach (var name in new[] { "zed.vet", "amy.vet", "mid.admin" })
            Assert.Equal(UserResultStatus.Ok, (await service.CreateAsync(TestAdmin, NewUser(name, name.EndsWith("admin") ? "ADMIN" : "VETTING"))).Status);
        var collector = (await service.CreateAsync(TestAdmin, NewUser("col.one", "CBO_COLLECTION", "cbo-5"))).Value!;

        var all = await service.ListAsync(new UserListQuery(null, null, null, 1, 50));
        Assert.Equal(new[] { "amy.vet", "col.one", "mid.admin", "zed.vet" }, all.Items.Select(i => i.Username)); // ordered by username
        Assert.Equal(new[] { "amy.vet", "zed.vet" }, (await service.ListAsync(new UserListQuery(UserRole.VETTING, null, null, 1, 50))).Items.Select(i => i.Username));
        Assert.Equal(new[] { "mid.admin" }, (await service.ListAsync(new UserListQuery(null, null, "MID", 1, 50))).Items.Select(i => i.Username)); // case-insensitive search

        Assert.Equal(UserResultStatus.Ok, (await service.UpdateAsync(TestAdmin, Guid.Parse(collector.Id), new api.DTOs.UpdateUserRequest { IsActive = false })).Status);
        Assert.Equal(new[] { "col.one" }, (await service.ListAsync(new UserListQuery(null, false, null, 1, 50))).Items.Select(i => i.Username));

        var history = (await service.GetAsync(Guid.Parse(collector.Id))).Value!.History;
        Assert.Equal(new[] { "DEACTIVATED", "CREATED" }, history.Select(h => h.Action)); // newest first, enum round-trips as text
        Assert.All(history, h => Assert.Equal("pg.admin", h.Actor));
    }

    [PostgresFact]
    public async Task TheFirstAdminBootstrap_WorksOnRealPostgres_AndASecondRunIsANoOp()
    {
        var services = new ServiceCollection();
        services.AddDbContext<AppDbContext>(o => o.UseNpgsql(ConnectionString));
        services.AddSingleton<Microsoft.AspNetCore.Identity.IPasswordHasher<AppUser>, Microsoft.AspNetCore.Identity.PasswordHasher<AppUser>>();
        services.AddSingleton(TimeProvider.System);
        await using var sp = services.BuildServiceProvider();
        var options = new api.Options.BootstrapOptions { AdminUsername = "pg.first.admin", AdminPassword = "first-admin-passphrase-1" };

        await AdminBootstrapper.RunAsync(sp, options, NullLogger.Instance);
        await AdminBootstrapper.RunAsync(sp, options, NullLogger.Instance);

        await using var check = NewDb();
        var admin = await check.Users.SingleAsync();
        Assert.Equal((UserRole.ADMIN, true), (admin.Role, admin.IsActive));
        Assert.Equal(1, await check.UserAudit.CountAsync());
    }

    // ── signatures and photos, arbitrated by real SQL ─────────────────────────────

    private static CollectionAttachment Attachment(string id, string collectionId, AttachmentKind kind, int slot) => new()
    {
        Id = id, CollectionId = collectionId, Kind = kind, Slot = slot, BlobName = $"{collectionId}/{id}",
        ContentType = "image/jpeg", SizeBytes = 10, Sha256 = new string('a', 64), UploadedBy = "someone",
    };

    private static byte[] JpegBytes(byte fill) => new byte[] { 0xFF, 0xD8, 0xFF, 0xE0, fill, fill, fill, fill };

    [PostgresFact]
    public async Task TheAttachmentTable_AllowsOneFilePerSlot_AndAFilesCollectionCannotBeDeleted()
    {
        await using (var seed = NewDb())
        {
            var collection = Collection("c1");
            collection.SubmittedBy = "someone";
            seed.CboCollections.Add(collection);
            seed.CollectionAttachments.Add(Attachment("a1", "c1", AttachmentKind.Photo, 1));
            await seed.SaveChangesAsync();
        }

        await using (var sameSlot = NewDb())
        {
            sameSlot.CollectionAttachments.Add(Attachment("a2", "c1", AttachmentKind.Photo, 1));
            await Assert.ThrowsAsync<DbUpdateException>(() => sameSlot.SaveChangesAsync()); // unique (collection, kind, slot)
        }

        await using (var otherSlot = NewDb())
        {
            otherSlot.CollectionAttachments.Add(Attachment("a3", "c1", AttachmentKind.Photo, 2));
            otherSlot.CollectionAttachments.Add(Attachment("a4", "c1", AttachmentKind.DonorSignature, 0));
            await otherSlot.SaveChangesAsync(); // a different slot, or a different kind, is fine
        }

        await using (var remove = NewDb())
        {
            remove.CboCollections.Remove(await remove.CboCollections.SingleAsync(c => c.Id == "c1"));
            await Assert.ThrowsAsync<DbUpdateException>(() => remove.SaveChangesAsync()); // evidence is not deleted with its collection
        }

        await using var check = NewDb();
        Assert.Equal(new[] { "DONOR_SIGNATURE", "PHOTO", "PHOTO" }, await check.Database
            .SqlQueryRaw<string>("SELECT kind AS \"Value\" FROM collection_attachments ORDER BY kind, slot").ToListAsync()); // stored as the wire names
    }

    [PostgresFact]
    public async Task TheSameFileUploadedTwiceAtOnce_IsStoredOnce_AndBothCallersSucceed()
    {
        await using (var seed = NewDb())
        {
            var collection = Collection("c1");
            collection.SubmittedBy = "someone";
            seed.CboCollections.Add(collection);
            await seed.SaveChangesAsync();
        }
        var store = new InMemoryAttachmentStore();

        async Task<AttachmentUploadResult> Upload()
        {
            await using var db = NewDb();
            return await new AttachmentService(db, store, NullLogger<AttachmentService>.Instance)
                .UploadAsync("c1", "a1", AttachmentKind.Photo, 0, "image/jpeg", JpegBytes(1), "someone", default);
        }
        var results = await Task.WhenAll(Enumerable.Range(0, 6).Select(_ => Task.Run(Upload)));

        Assert.All(results, r => Assert.True(r.Outcome is AttachmentOutcome.Stored or AttachmentOutcome.AlreadyReceived, $"unexpected {r.Outcome}"));
        Assert.Contains(results, r => r.Outcome == AttachmentOutcome.Stored);
        await using var check = NewDb();
        Assert.Equal(1, await check.CollectionAttachments.CountAsync());
        Assert.Equal(new[] { "c1/a1" }, store.Names);
    }

    [PostgresFact]
    public async Task TwoDifferentFilesForOneSlotAtOnce_ExactlyOneWins_AndTheLoserLeavesNothingBehind()
    {
        await using (var seed = NewDb())
        {
            var collection = Collection("c1");
            collection.SubmittedBy = "someone";
            seed.CboCollections.Add(collection);
            await seed.SaveChangesAsync();
        }
        var store = new InMemoryAttachmentStore();

        async Task<AttachmentUploadResult> Upload(string id, byte fill)
        {
            await using var db = NewDb();
            return await new AttachmentService(db, store, NullLogger<AttachmentService>.Instance)
                .UploadAsync("c1", id, AttachmentKind.DeliveryNote, 0, "image/jpeg", JpegBytes(fill), "someone", default);
        }
        var results = await Task.WhenAll(Enumerable.Range(0, 6).Select(i => Task.Run(() => Upload("a" + i, (byte)i))));

        Assert.Equal(1, results.Count(r => r.Outcome == AttachmentOutcome.Stored));
        Assert.All(results.Where(r => r.Outcome != AttachmentOutcome.Stored), r => Assert.Equal(AttachmentOutcome.Conflict, r.Outcome));
        await using var check = NewDb();
        var winner = await check.CollectionAttachments.SingleAsync();
        Assert.Equal(new[] { winner.BlobName }, store.Names); // the losers' bytes were cleaned up, nothing orphaned
    }
}
