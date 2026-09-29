using external_api_sim;

// Simulator for the client's (Foodspace's) existing API. All endpoints are listed in ExternalApiEndpoints.cs.
var builder = WebApplication.CreateBuilder(args);

builder.Services.AddSingleton<SampleStore>();
builder.Services.AddOpenApi();

var app = builder.Build();

app.MapOpenApi(); // /openapi/v1.json: the endpoint list as a spec the client can import
app.MapExternalApi();

app.Run();
