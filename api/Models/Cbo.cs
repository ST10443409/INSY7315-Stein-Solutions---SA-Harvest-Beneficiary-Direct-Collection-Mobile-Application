namespace api.Models;

/// <summary>Room: CboEntity (table "cbos").</summary>
public class Cbo : SyncedEntity
{
    public required string Name { get; set; }
    public required string Status { get; set; }
    public required string Meta { get; set; }
    public Tone Tone { get; set; }
}
