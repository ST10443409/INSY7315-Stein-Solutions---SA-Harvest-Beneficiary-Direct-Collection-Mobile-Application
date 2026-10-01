namespace external_api_sim;

/// <summary>
/// THE list of Foodspace API endpoints SA Harvest needs. Every endpoint of the external-API simulator
/// is declared in this one file so it can be walked through with the client.
///
///   DIRECTION  METHOD  PATH                                  PURPOSE                                   USED BY (backend issue)
///   ---------  ------  ------------------------------------  ----------------------------------------  -----------------------
///   PULL       GET     /api/external/cbos                    CBO registry                              Form 1 (#36)
///   PULL       GET     /api/external/beneficiaries           Beneficiary records for vetting           Form 2 fetch (#43)
///   PULL       GET     /api/external/beneficiaries/{id}      One beneficiary record                    Form 2 fetch (#43)
///   PUSH       POST    /api/external/cbo-collections         A completed collection + product lines    Form 1 forwarding (#36)
///   PUSH       POST    /api/external/vetting-decisions       A vetting officer's decision              Form 2 forwarding (#47)
///   SYSTEM     GET     /api/external/status                  Availability check                        monitoring
///   LEGACY     POST    /api/external/sync                    Generic payload from the first prototype  current queue worker
///   SIM ONLY   GET     /api/external/received                Shows what has been pushed to the sim     demos and debugging
///   SIM ONLY   POST    /api/external/simulate/outage         Make every endpoint answer 503 (or ?status=)  demos and tests
///   SIM ONLY   POST    /api/external/simulate/recover        End the simulated outage                  demos and tests
///
/// PULL = we read from Foodspace. PUSH = we write to Foodspace. Payload shapes are in Contracts.cs.
/// The same list is available as OpenAPI JSON at /openapi/v1.json.
///
/// Assumed for now (to confirm with the client): no authentication on these endpoints (set Simulator:ApiKey to
/// require an X-Api-Key header, to exercise the backend's key handling), and pushes are
/// idempotent upserts keyed by the client-generated "id", so a retried sync never creates duplicates.
/// </summary>
public static class ExternalApiEndpoints
{
    private const string Pull = "PULL from Foodspace";
    private const string Push = "PUSH to Foodspace";

    public static void MapExternalApi(this WebApplication app)
    {
        // Simulator controls live outside the group below so they keep working during a simulated outage.
        app.MapPost("/api/external/simulate/outage", (int? status, SimulatedFaults faults) =>
            {
                faults.StartOutage(status is >= 400 and <= 599 ? status.Value : 503);
                return Results.Ok(new { Outage = true, Status = faults.OutageStatus });
            })
            .WithTags("Simulator only")
            .WithSummary("Start an outage")
            .WithDescription("Every Foodspace endpoint answers with the given error status (default 503) until /simulate/recover is called.");

        app.MapPost("/api/external/simulate/recover", (SimulatedFaults faults) =>
            {
                faults.EndOutage();
                return Results.Ok(new { Outage = false });
            })
            .WithTags("Simulator only")
            .WithSummary("End the outage");

        var api = app.MapGroup("/api/external");

        // Applies to every Foodspace endpoint: a simulated outage, and the API key when one is configured
        // (Simulator:ApiKey). Real Foodspace auth is still to be confirmed; the default is no authentication.
        api.AddEndpointFilter(async (context, next) =>
        {
            var faults = context.HttpContext.RequestServices.GetRequiredService<SimulatedFaults>();
            if (faults.OutageStatus is { } status) return Results.StatusCode(status);

            var expectedKey = context.HttpContext.RequestServices.GetRequiredService<IConfiguration>()["Simulator:ApiKey"];
            if (!string.IsNullOrEmpty(expectedKey)
                && context.HttpContext.Request.Headers["X-Api-Key"] != expectedKey)
                return Results.Unauthorized();

            return await next(context);
        });

        // ═════════════ PULL: data we read from Foodspace ═════════════

        api.MapGet("/cbos", (SampleStore store) => Results.Ok(store.Cbos))
            .WithTags(Pull)
            .WithSummary("List CBOs")
            .WithDescription("The community-based organisations that collect surplus food. Maps to the app's 'cbos' table.")
            .Produces<List<CboDto>>();

        api.MapGet("/beneficiaries", (string? province, SampleStore store) =>
                Results.Ok(store.Beneficiaries.Where(b =>
                    province is null || b.Province.Equals(province, StringComparison.OrdinalIgnoreCase))))
            .WithTags(Pull)
            .WithSummary("List beneficiary records")
            .WithDescription("Beneficiary organisations awaiting vetting, with the full profile a vetting officer reviews on Form 2. "
                             + "Optional ?province= filter. Maps to the app's 'foodspace_beneficiary_records' table.")
            .Produces<List<BeneficiaryDto>>();

        api.MapGet("/beneficiaries/{id}", (string id, SampleStore store) =>
                store.Beneficiaries.FirstOrDefault(b => b.Id == id) is { } found
                    ? Results.Ok(found)
                    : Results.NotFound(new { Error = $"No beneficiary with id '{id}'." }))
            .WithTags(Pull)
            .WithSummary("Get one beneficiary record")
            .Produces<BeneficiaryDto>()
            .Produces(StatusCodes.Status404NotFound);

        // ═════════════ PUSH: data we write to Foodspace ═════════════

        api.MapPost("/cbo-collections", (CboCollectionDto collection, SampleStore store) =>
            {
                var errors = new Dictionary<string, string[]>();
                Require(errors, nameof(collection.Id), collection.Id);
                Require(errors, nameof(collection.CboId), collection.CboId);
                Require(errors, nameof(collection.DonorName), collection.DonorName);
                if (collection.ProductLines is null || collection.ProductLines.Count == 0)
                    errors[nameof(collection.ProductLines)] = new[] { "At least one product line is required." };
                if (errors.Count > 0) return Results.ValidationProblem(errors);

                store.ReceivedCollections[collection.Id] = collection; // upsert by id
                return Results.Ok(new { Status = "Success", ReceivedId = collection.Id });
            })
            .WithTags(Push)
            .WithSummary("Submit a CBO collection (Form 1)")
            .WithDescription("A completed collection with its product lines. Idempotent: re-sending the same id replaces the earlier copy.")
            .Produces(StatusCodes.Status200OK)
            .ProducesValidationProblem();

        api.MapPost("/vetting-decisions", (VettingDecisionDto decision, SampleStore store) =>
            {
                var errors = new Dictionary<string, string[]>();
                Require(errors, nameof(decision.Id), decision.Id);
                Require(errors, nameof(decision.FoodspaceRecordId), decision.FoodspaceRecordId);
                Require(errors, nameof(decision.OfficerId), decision.OfficerId);
                if (decision.Outcome is not ("APPROVE" or "REJECT" or "FLAG"))
                    errors[nameof(decision.Outcome)] = new[] { "Must be APPROVE, REJECT or FLAG." };
                if (errors.Count == 0 && store.Beneficiaries.All(b => b.Id != decision.FoodspaceRecordId))
                    errors[nameof(decision.FoodspaceRecordId)] = new[] { "No such beneficiary record in Foodspace." };
                if (errors.Count > 0) return Results.ValidationProblem(errors);

                store.ReceivedDecisions[decision.Id] = decision; // upsert by id
                return Results.Ok(new { Status = "Success", ReceivedId = decision.Id });
            })
            .WithTags(Push)
            .WithSummary("Submit a vetting decision (Form 2)")
            .WithDescription("A vetting officer's decision on a beneficiary. Idempotent by id. The record must exist in Foodspace.")
            .Produces(StatusCodes.Status200OK)
            .ProducesValidationProblem();

        // ═════════════ SYSTEM ═════════════

        api.MapGet("/status", () => Results.Ok(new { Status = "Online" }))
            .WithTags("System")
            .WithSummary("Availability check");

        // ═════════════ LEGACY (first queue prototype) ═════════════

        api.MapPost("/sync", async (SyncPayload payload, ILogger<SyncPayload> logger) =>
            {
                logger.LogInformation("External API Simulator received payload with ID: {Id} at {Time}", payload.Id, DateTime.UtcNow);
                await Task.Delay(500); // simulate processing time
                return Results.Ok(new { Status = "Success", ReceivedId = payload.Id });
            })
            .WithTags("Legacy")
            .WithSummary("Generic sync payload (superseded by the typed push endpoints)");

        // ═════════════ SIMULATOR ONLY (not part of Foodspace's real API) ═════════════

        api.MapGet("/received", (SampleStore store) => Results.Ok(new
            {
                CboCollections = store.ReceivedCollections.Values,
                VettingDecisions = store.ReceivedDecisions.Values,
            }))
            .WithTags("Simulator only")
            .WithSummary("Show everything pushed to the simulator so far");
    }

    private static void Require(Dictionary<string, string[]> errors, string field, string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) errors[field] = new[] { $"{field} is required." };
    }
}
