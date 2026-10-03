using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using api.Data;
using api.Models;
using api.Services;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace api.Tests;

/// <summary>
/// Signatures and photos (Form 1) through the real pipeline: authentication, the author rule, size and type checks (from the
/// bytes, not the header), retry safety, conflicts, an unreachable storage account, and the Admin's read-only view. Blob storage
/// is the in-memory fake, so nothing here touches Azure.
/// </summary>
public class AttachmentUploadEndpointTests : IClassFixture<ApiFactory>
{
    private const string Collector = "cbo_test_user";
    private const string OtherCollector = "cbo_other_user";
    private const string Admin = "admin_test_user";

    private readonly ApiFactory _factory;

    public AttachmentUploadEndpointTests(ApiFactory factory) => _factory = factory;

    // ── helpers ────────────────────────────────────────────────────────────────────

    private async Task<HttpClient> ClientFor(string? username)
    {
        var client = _factory.CreateClient();
        if (username is null) return client;
        var login = await client.PostAsJsonAsync("/api/auth/login", new { username, password = ApiFactory.Password });
        login.EnsureSuccessStatusCode();
        var token = (await login.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("token").GetString()!;
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return client;
    }

    /// <summary>A collection the given user has submitted (through the real sync endpoint), so it is theirs by the author rule.</summary>
    private async Task<string> SubmitCollection(string username)
    {
        var id = Guid.NewGuid().ToString();
        var client = await ClientFor(username);
        var record = new Dictionary<string, object?>
        {
            ["id"] = id, ["cboId"] = "cbo-001", ["arrivalTime"] = "09:42", ["departureTime"] = "10:26", ["donorName"] = "Jane Donor",
            ["donorSigned"] = true, ["cboSigned"] = true, ["deliveryNote"] = "DN-" + id, ["noteAttached"] = false, ["collectNotes"] = "",
            ["shots"] = new[] { true, false, false, false }, ["latitude"] = null, ["longitude"] = null,
            ["createdAt"] = 1_700_000_000_000L, ["updatedAt"] = 1_700_000_000_000L,
            ["productLines"] = new[]
            {
                new Dictionary<string, object?> { ["id"] = Guid.NewGuid().ToString(), ["collectionId"] = id, ["category"] = "Fruit", ["kg"] = "42.5", ["notes"] = null },
            },
        };
        var response = await client.PostAsJsonAsync("/api/cbo-collection/sync", new { records = new[] { record } });
        response.EnsureSuccessStatusCode();
        return id;
    }

    private static byte[] Png(int size = 200, byte fill = 7)
    {
        var bytes = new byte[size];
        Array.Fill(bytes, fill);
        new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A }.CopyTo(bytes, 0);
        return bytes;
    }

    private static byte[] Jpeg(int size = 300, byte fill = 9)
    {
        var bytes = new byte[size];
        Array.Fill(bytes, fill);
        new byte[] { 0xFF, 0xD8, 0xFF, 0xE0 }.CopyTo(bytes, 0);
        return bytes;
    }

    private static string Url(string collectionId, string attachmentId, string? kind, int? slot = null)
    {
        var query = new List<string>();
        if (kind is not null) query.Add("kind=" + kind);
        if (slot is not null) query.Add("slot=" + slot);
        return $"/api/cbo-collection/{collectionId}/attachments/{attachmentId}" + (query.Count > 0 ? "?" + string.Join("&", query) : "");
    }

    private static Task<HttpResponseMessage> Put(
        HttpClient client, string collectionId, string attachmentId, string? kind, byte[] bytes, string? contentType = "image/png", int? slot = null)
    {
        var content = new ByteArrayContent(bytes);
        if (contentType is not null) content.Headers.ContentType = MediaTypeHeaderValue.Parse(contentType);
        return client.PutAsync(Url(collectionId, attachmentId, kind, slot), content);
    }

    private static async Task<JsonElement> Body(HttpResponseMessage response) => await response.Content.ReadFromJsonAsync<JsonElement>();

    private static async Task<string?> ErrorCode(HttpResponseMessage response) =>
        (await Body(response)).GetProperty("error").GetProperty("code").GetString();

    private async Task<List<CollectionAttachment>> RowsFor(string collectionId)
    {
        using var scope = _factory.Services.CreateScope();
        return await scope.ServiceProvider.GetRequiredService<AppDbContext>().CollectionAttachments
            .AsNoTracking().Where(a => a.CollectionId == collectionId).ToListAsync();
    }

    private IEnumerable<string> BlobsFor(string collectionId) => _factory.AttachmentStore.Names.Where(n => n.StartsWith(collectionId + "/"));

    /// <summary>Reports the body as having no known length, so it travels chunked and the declared-size shortcut cannot help.</summary>
    private sealed class UnknownLengthContent : HttpContent
    {
        private readonly byte[] _bytes;

        public UnknownLengthContent(byte[] bytes, string contentType)
        {
            _bytes = bytes;
            Headers.ContentType = MediaTypeHeaderValue.Parse(contentType);
        }

        protected override Task SerializeToStreamAsync(Stream stream, System.Net.TransportContext? context) => stream.WriteAsync(_bytes).AsTask();

        protected override bool TryComputeLength(out long length)
        {
            length = 0;
            return false;
        }
    }

    // ── access ─────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task WithoutAToken_Is401()
    {
        var response = await Put(await ClientFor(null), "c1", "a1", "PHOTO", Png(), slot: 0);

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task AVettingOfficer_Is403()
    {
        var response = await Put(await ClientFor("vetting_test_user"), "c1", "a1", "PHOTO", Png(), slot: 0);

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    // ── storing ────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task ASignature_IsStored_WithTheServerChosenBlobNameAndTheRealHash()
    {
        var collection = await SubmitCollection(Collector);
        var attachment = Guid.NewGuid().ToString();
        var bytes = Png(500, fill: 3);

        var response = await Put(await ClientFor(Collector), collection, attachment, "DONOR_SIGNATURE", bytes);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var data = (await Body(response)).GetProperty("data");
        Assert.Equal(attachment, data.GetProperty("attachmentId").GetString());
        Assert.False(data.GetProperty("alreadyReceived").GetBoolean());
        Assert.Equal(500, data.GetProperty("sizeBytes").GetInt64());

        var row = Assert.Single(await RowsFor(collection));
        Assert.Equal(attachment, row.Id);
        Assert.Equal(AttachmentKind.DonorSignature, row.Kind);
        Assert.Equal(0, row.Slot);
        Assert.Equal($"{collection}/{attachment}", row.BlobName);
        Assert.Equal("image/png", row.ContentType);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(bytes)), row.Sha256);
        Assert.Equal(Collector, row.UploadedBy);
        Assert.Equal(bytes, _factory.AttachmentStore.BytesOf(row.BlobName));
    }

    [Fact]
    public async Task APhoto_IsStored_InItsSlot_AsJpeg()
    {
        var collection = await SubmitCollection(Collector);
        var attachment = Guid.NewGuid().ToString();

        var response = await Put(await ClientFor(Collector), collection, attachment, "photo", Jpeg(), "image/jpeg", slot: 2);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var row = Assert.Single(await RowsFor(collection));
        Assert.Equal(AttachmentKind.Photo, row.Kind);
        Assert.Equal(2, row.Slot);
        Assert.Equal("image/jpeg", row.ContentType);
    }

    [Fact]
    public async Task EveryKindAndSeveralPhotos_CanBeStoredForOneCollection()
    {
        var collection = await SubmitCollection(Collector);
        var client = await ClientFor(Collector);

        foreach (var (kind, slot) in new[] { ("DONOR_SIGNATURE", 0), ("CBO_SIGNATURE", 0), ("DELIVERY_NOTE", 0), ("PHOTO", 0), ("PHOTO", 1), ("PHOTO", 2) })
            Assert.Equal(HttpStatusCode.OK, (await Put(client, collection, Guid.NewGuid().ToString(), kind, Jpeg(), "image/jpeg", slot)).StatusCode);

        Assert.Equal(6, (await RowsFor(collection)).Count);
        Assert.Equal(6, BlobsFor(collection).Count());
    }

    [Fact]
    public async Task AnAdmin_CanUploadForTheirOwnCollection()
    {
        var collection = await SubmitCollection(Admin);

        var response = await Put(await ClientFor(Admin), collection, Guid.NewGuid().ToString(), "PHOTO", Jpeg(), "image/jpeg", slot: 0);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(Admin, Assert.Single(await RowsFor(collection)).UploadedBy);
    }

    // ── whose collection ───────────────────────────────────────────────────────────

    [Fact]
    public async Task ACollectionThatDoesNotExist_Is404()
    {
        var response = await Put(await ClientFor(Collector), Guid.NewGuid().ToString(), Guid.NewGuid().ToString(), "PHOTO", Jpeg(), "image/jpeg", slot: 0);

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        Assert.Equal("NOT_FOUND", await ErrorCode(response));
    }

    [Fact]
    public async Task AnotherCollectorsCollection_Is404_AndNothingIsStored()
    {
        var collection = await SubmitCollection(Collector);

        var response = await Put(await ClientFor(OtherCollector), collection, Guid.NewGuid().ToString(), "PHOTO", Jpeg(), "image/jpeg", slot: 0);

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode); // not 403: a stranger learns nothing about which ids exist
        Assert.Empty(await RowsFor(collection));
        Assert.Empty(BlobsFor(collection));
    }

    [Fact]
    public async Task AnAdmin_CannotAttachToACollectorsCollection()
    {
        var collection = await SubmitCollection(Collector);

        var response = await Put(await ClientFor(Admin), collection, Guid.NewGuid().ToString(), "PHOTO", Jpeg(), "image/jpeg", slot: 0);

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        Assert.Empty(BlobsFor(collection));
    }

    // ── the request itself ─────────────────────────────────────────────────────────

    [Theory]
    [InlineData(null, 0)]                // no kind
    [InlineData("SELFIE", 0)]            // not a kind
    [InlineData("PHOTO", 20)]            // past the last photo slot
    [InlineData("PHOTO", -1)]
    [InlineData("DONOR_SIGNATURE", 1)]   // only photos have slots
    [InlineData("DELIVERY_NOTE", 3)]
    public async Task ABadKindOrSlot_Is400_WithTheFieldNamed(string? kind, int slot)
    {
        var collection = await SubmitCollection(Collector);

        var response = await Put(await ClientFor(Collector), collection, Guid.NewGuid().ToString(), kind, Png(), slot: slot);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Equal("VALIDATION_FAILED", await ErrorCode(response));
        Assert.Empty(BlobsFor(collection));
    }

    [Fact]
    public async Task AnIdThatCouldBeAPathTrick_Is400_AndNothingIsStored()
    {
        var collection = await SubmitCollection(Collector);
        var before = _factory.AttachmentStore.Names.Count;
        var client = await ClientFor(Collector);

        foreach (var attachment in new[] { "has%20space", "a.b", new string('x', 65), "-dash" })
        {
            var response = await Put(client, collection, attachment, "PHOTO", Jpeg(), "image/jpeg", slot: 0);
            Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        }

        Assert.Equal(before, _factory.AttachmentStore.Names.Count);
    }

    [Fact]
    public async Task AnEmptyBody_Is400()
    {
        var collection = await SubmitCollection(Collector);

        var response = await Put(await ClientFor(Collector), collection, Guid.NewGuid().ToString(), "PHOTO", Array.Empty<byte>(), "image/jpeg", slot: 0);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    // ── what the bytes are ─────────────────────────────────────────────────────────

    [Fact]
    public async Task PngBytes_DeclaredAsJpeg_Is415()
    {
        var collection = await SubmitCollection(Collector);

        var response = await Put(await ClientFor(Collector), collection, Guid.NewGuid().ToString(), "PHOTO", Png(), "image/jpeg", slot: 0);

        Assert.Equal(HttpStatusCode.UnsupportedMediaType, response.StatusCode);
        Assert.Equal("UNSUPPORTED_MEDIA_TYPE", await ErrorCode(response));
        Assert.Empty(BlobsFor(collection));
    }

    [Theory]
    [InlineData("text/plain")]
    [InlineData("application/octet-stream")]
    [InlineData("image/gif")]
    [InlineData(null)]
    public async Task ADeclaredTypeThatIsNotJpegOrPng_Is415(string? declared)
    {
        var collection = await SubmitCollection(Collector);

        var response = await Put(await ClientFor(Collector), collection, Guid.NewGuid().ToString(), "PHOTO", Jpeg(), declared, slot: 0);

        Assert.Equal(HttpStatusCode.UnsupportedMediaType, response.StatusCode);
        Assert.Empty(BlobsFor(collection));
    }

    [Fact]
    public async Task ADisguisedFile_ClaimingToBeAnImage_Is415()
    {
        var collection = await SubmitCollection(Collector);
        var script = System.Text.Encoding.UTF8.GetBytes("<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>");

        var response = await Put(await ClientFor(Collector), collection, Guid.NewGuid().ToString(), "PHOTO", script, "image/png", slot: 0);

        Assert.Equal(HttpStatusCode.UnsupportedMediaType, response.StatusCode);
        Assert.Empty(BlobsFor(collection));
    }

    // ── size ───────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task ASignatureAtTheCap_IsAccepted_AndOneByteOverIs413()
    {
        var collection = await SubmitCollection(Collector);
        var client = await ClientFor(Collector);

        var atCap = await Put(client, collection, Guid.NewGuid().ToString(), "DONOR_SIGNATURE", Png((int)AttachmentRules.MaxSignatureBytes));
        var over = await Put(client, collection, Guid.NewGuid().ToString(), "CBO_SIGNATURE", Png((int)AttachmentRules.MaxSignatureBytes + 1));

        Assert.Equal(HttpStatusCode.OK, atCap.StatusCode);
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, over.StatusCode);
        Assert.Equal("PAYLOAD_TOO_LARGE", await ErrorCode(over));
        Assert.Single(await RowsFor(collection));
    }

    [Fact]
    public async Task APhotoOverTheCap_Is413()
    {
        var collection = await SubmitCollection(Collector);

        var response = await Put(await ClientFor(Collector), collection, Guid.NewGuid().ToString(), "PHOTO", Jpeg((int)AttachmentRules.MaxPhotoBytes + 1), "image/jpeg", slot: 0);

        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, response.StatusCode);
        Assert.Empty(BlobsFor(collection));
    }

    [Fact]
    public async Task TheCapHolds_EvenWhenTheBodyDoesNotSayHowLongItIs()
    {
        var collection = await SubmitCollection(Collector);
        var client = await ClientFor(Collector);
        var tooBigForASignature = new UnknownLengthContent(Png((int)AttachmentRules.MaxSignatureBytes + 1), "image/png");

        var response = await client.PutAsync(Url(collection, Guid.NewGuid().ToString(), "DONOR_SIGNATURE"), tooBigForASignature);

        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, response.StatusCode);
        Assert.Empty(BlobsFor(collection));
    }

    // ── retries and conflicts ──────────────────────────────────────────────────────

    [Fact]
    public async Task SendingTheSameFileAgain_IsASuccessThatChangesNothing()
    {
        var collection = await SubmitCollection(Collector);
        var attachment = Guid.NewGuid().ToString();
        var client = await ClientFor(Collector);
        var bytes = Jpeg(400, fill: 5);
        var putsBefore = _factory.AttachmentStore.PutCount;

        var first = await Put(client, collection, attachment, "PHOTO", bytes, "image/jpeg", slot: 1);
        var again = await Put(client, collection, attachment, "PHOTO", bytes, "image/jpeg", slot: 1);

        Assert.Equal(HttpStatusCode.OK, first.StatusCode);
        Assert.Equal(HttpStatusCode.OK, again.StatusCode);
        Assert.True((await Body(again)).GetProperty("data").GetProperty("alreadyReceived").GetBoolean());
        Assert.Single(await RowsFor(collection));
        Assert.Equal(putsBefore + 1, _factory.AttachmentStore.PutCount); // the second call never touched storage
    }

    [Fact]
    public async Task ARetryCarryingDifferentBytes_DoesNotOverwriteTheFirstFile()
    {
        var collection = await SubmitCollection(Collector);
        var attachment = Guid.NewGuid().ToString();
        var client = await ClientFor(Collector);
        var original = Jpeg(400, fill: 5);

        await Put(client, collection, attachment, "PHOTO", original, "image/jpeg", slot: 0);
        var retry = await Put(client, collection, attachment, "PHOTO", Jpeg(400, fill: 6), "image/jpeg", slot: 0);

        Assert.Equal(HttpStatusCode.OK, retry.StatusCode);
        Assert.Equal(original, _factory.AttachmentStore.BytesOf($"{collection}/{attachment}")); // first write wins, like records
    }

    [Fact]
    public async Task ADifferentFileForASlotThatIsTaken_Is409_AndLeavesTheFirstAlone()
    {
        var collection = await SubmitCollection(Collector);
        var client = await ClientFor(Collector);
        var first = Guid.NewGuid().ToString();
        await Put(client, collection, first, "PHOTO", Jpeg(), "image/jpeg", slot: 1);

        var second = await Put(client, collection, Guid.NewGuid().ToString(), "PHOTO", Jpeg(fill: 1), "image/jpeg", slot: 1);

        Assert.Equal(HttpStatusCode.Conflict, second.StatusCode);
        Assert.Equal("CONFLICT", await ErrorCode(second));
        Assert.Equal(first, Assert.Single(await RowsFor(collection)).Id);
        Assert.Single(BlobsFor(collection));
    }

    [Fact]
    public async Task ReusingAnIdForADifferentSlot_Is409()
    {
        var collection = await SubmitCollection(Collector);
        var client = await ClientFor(Collector);
        var attachment = Guid.NewGuid().ToString();
        await Put(client, collection, attachment, "PHOTO", Jpeg(), "image/jpeg", slot: 0);

        var response = await Put(client, collection, attachment, "PHOTO", Jpeg(), "image/jpeg", slot: 1);

        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
    }

    [Fact]
    public async Task TheSameAttachmentIdOnAnotherCollection_Is409_NotALeak()
    {
        var mine = await SubmitCollection(Collector);
        var alsoMine = await SubmitCollection(Collector);
        var client = await ClientFor(Collector);
        var attachment = Guid.NewGuid().ToString();
        await Put(client, mine, attachment, "PHOTO", Jpeg(), "image/jpeg", slot: 0);

        var response = await Put(client, alsoMine, attachment, "PHOTO", Jpeg(), "image/jpeg", slot: 0);

        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
        Assert.Empty(await RowsFor(alsoMine));
    }

    // ── storage trouble ────────────────────────────────────────────────────────────

    [Fact]
    public async Task WhenStorageIsDown_Is503_NothingIsRecorded_AndTheSameCallWorksOnceItIsBack()
    {
        var collection = await SubmitCollection(Collector);
        var attachment = Guid.NewGuid().ToString();
        var client = await ClientFor(Collector);
        HttpResponseMessage down, back;

        _factory.AttachmentStore.Unavailable = true;
        try
        {
            down = await Put(client, collection, attachment, "PHOTO", Jpeg(), "image/jpeg", slot: 0);
        }
        finally
        {
            _factory.AttachmentStore.Unavailable = false;
        }
        var rowsWhileDown = await RowsFor(collection);
        back = await Put(client, collection, attachment, "PHOTO", Jpeg(), "image/jpeg", slot: 0);

        Assert.Equal(HttpStatusCode.ServiceUnavailable, down.StatusCode);
        Assert.Equal("SERVICE_UNAVAILABLE", await ErrorCode(down));
        Assert.Empty(rowsWhileDown);
        Assert.Equal(HttpStatusCode.OK, back.StatusCode);
        Assert.False((await Body(back)).GetProperty("data").GetProperty("alreadyReceived").GetBoolean());
        Assert.Single(await RowsFor(collection));
    }

    [Fact]
    public async Task TheErrorForAnOutage_NeverRevealsTheStorageDetail()
    {
        var collection = await SubmitCollection(Collector);
        _factory.AttachmentStore.Unavailable = true;
        string text;
        try
        {
            var response = await Put(await ClientFor(Collector), collection, Guid.NewGuid().ToString(), "PHOTO", Jpeg(), "image/jpeg", slot: 0);
            text = await response.Content.ReadAsStringAsync();
        }
        finally
        {
            _factory.AttachmentStore.Unavailable = false;
        }

        Assert.DoesNotContain("simulated outage", text);
        Assert.DoesNotContain("blob", text, StringComparison.OrdinalIgnoreCase);
    }

    // ── the Admin's read-only view ─────────────────────────────────────────────────

    [Fact]
    public async Task AnAdmin_ListsAndReadsWhatWasUploaded_ByteForByte()
    {
        var collection = await SubmitCollection(Collector);
        var collector = await ClientFor(Collector);
        var signature = Guid.NewGuid().ToString();
        var photo = Guid.NewGuid().ToString();
        var png = Png(321, fill: 2);
        var jpeg = Jpeg(654, fill: 4);
        await Put(collector, collection, signature, "DONOR_SIGNATURE", png);
        await Put(collector, collection, photo, "PHOTO", jpeg, "image/jpeg", slot: 3);
        var admin = await ClientFor(Admin);

        var list = await admin.GetAsync($"/api/admin/collections/{collection}/attachments");
        var file = await admin.GetAsync($"/api/admin/attachments/{photo}");

        Assert.Equal(HttpStatusCode.OK, list.StatusCode);
        var items = (await Body(list)).GetProperty("data").GetProperty("attachments").EnumerateArray().ToList();
        Assert.Equal(2, items.Count);
        var listed = items.Single(i => i.GetProperty("id").GetString() == photo);
        Assert.Equal("PHOTO", listed.GetProperty("kind").GetString());
        Assert.Equal(3, listed.GetProperty("slot").GetInt32());
        Assert.Equal("image/jpeg", listed.GetProperty("contentType").GetString());
        Assert.Equal(654, listed.GetProperty("sizeBytes").GetInt64());
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(jpeg)), listed.GetProperty("sha256").GetString());
        Assert.Equal(Collector, listed.GetProperty("uploadedBy").GetString());
        Assert.DoesNotContain("blobName", await list.Content.ReadAsStringAsync(), StringComparison.OrdinalIgnoreCase); // no storage address in the answer

        Assert.Equal(HttpStatusCode.OK, file.StatusCode);
        Assert.Equal("image/jpeg", file.Content.Headers.ContentType?.MediaType);
        Assert.Equal(jpeg, await file.Content.ReadAsByteArrayAsync());
    }

    [Fact]
    public async Task APicture_IsNeverCached_NeverSniffed_AndCannotRunAnything()
    {
        var collection = await SubmitCollection(Collector);
        var attachment = Guid.NewGuid().ToString();
        await Put(await ClientFor(Collector), collection, attachment, "PHOTO", Jpeg(), "image/jpeg", slot: 0);

        var response = await (await ClientFor(Admin)).GetAsync($"/api/admin/attachments/{attachment}");

        Assert.True(response.Headers.CacheControl?.NoStore);
        Assert.True(response.Headers.CacheControl?.Private);
        Assert.Equal("nosniff", response.Headers.GetValues("X-Content-Type-Options").Single());
        Assert.Contains("sandbox", response.Headers.GetValues("Content-Security-Policy").Single());
    }

    [Fact]
    public async Task ACollectionWithNoFiles_ListsAsEmpty_AndOneThatDoesNotExistIs404()
    {
        var collection = await SubmitCollection(Collector);
        var admin = await ClientFor(Admin);

        var empty = await admin.GetAsync($"/api/admin/collections/{collection}/attachments");
        var missing = await admin.GetAsync($"/api/admin/collections/{Guid.NewGuid()}/attachments");

        Assert.Equal(HttpStatusCode.OK, empty.StatusCode);
        Assert.Empty((await Body(empty)).GetProperty("data").GetProperty("attachments").EnumerateArray());
        Assert.Equal(HttpStatusCode.NotFound, missing.StatusCode);
    }

    [Fact]
    public async Task AnAttachmentThatDoesNotExist_Is404()
    {
        var response = await (await ClientFor(Admin)).GetAsync($"/api/admin/attachments/{Guid.NewGuid()}");

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task ARecordWhoseFileIsMissingFromStorage_Is404_NotAnError()
    {
        var collection = await SubmitCollection(Collector);
        var attachment = Guid.NewGuid().ToString();
        await Put(await ClientFor(Collector), collection, attachment, "PHOTO", Jpeg(), "image/jpeg", slot: 0);
        await _factory.AttachmentStore.DeleteAsync($"{collection}/{attachment}", default);

        var response = await (await ClientFor(Admin)).GetAsync($"/api/admin/attachments/{attachment}");

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task ReadingWhileStorageIsDown_Is503()
    {
        var collection = await SubmitCollection(Collector);
        var attachment = Guid.NewGuid().ToString();
        await Put(await ClientFor(Collector), collection, attachment, "PHOTO", Jpeg(), "image/jpeg", slot: 0);
        var admin = await ClientFor(Admin);

        _factory.AttachmentStore.Unavailable = true;
        HttpResponseMessage response;
        try
        {
            response = await admin.GetAsync($"/api/admin/attachments/{attachment}");
        }
        finally
        {
            _factory.AttachmentStore.Unavailable = false;
        }

        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
    }

    [Theory]
    [InlineData(Collector)]
    [InlineData("vetting_test_user")]
    public async Task OnlyAnAdmin_CanReadFiles(string username)
    {
        var client = await ClientFor(username);

        Assert.Equal(HttpStatusCode.Forbidden, (await client.GetAsync("/api/admin/attachments/a1")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await client.GetAsync("/api/admin/collections/c1/attachments")).StatusCode);
    }
}
