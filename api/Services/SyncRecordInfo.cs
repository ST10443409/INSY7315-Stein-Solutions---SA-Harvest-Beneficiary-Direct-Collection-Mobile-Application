using api.Models;

namespace api.Services;

/// <summary>What an Admin sees to recognise a record and who sent it. One definition, shared by the failed-sync list (#50) and the user activity view (#51).</summary>
public static class SyncRecordInfo
{
    /// <summary>South Africa is UTC+2 all year (no daylight saving). The calendar day every date in the app means.</summary>
    public static readonly TimeSpan SouthAfrica = TimeSpan.FromHours(2);

    /// <summary>A short line that identifies the record to a person: the donor, or the beneficiary and outcome.</summary>
    public static string LabelOf(ForwardedEntity record) => record switch
    {
        CboCollection c => $"{c.DonorName} · delivery note {c.DeliveryNote}",
        VettingDecision d => $"{d.Outcome} · beneficiary {d.FoodspaceRecordId}",
        _ => record.Id
    };

    /// <summary>The collector (Form 1) or officer (Form 2) who sent it. Null for a Form 1 record from before submitters were recorded.</summary>
    public static string? SubmitterOf(ForwardedEntity record) => record switch
    {
        CboCollection c => c.SubmittedBy,
        VettingDecision d => d.OfficerId,
        _ => null
    };
}
