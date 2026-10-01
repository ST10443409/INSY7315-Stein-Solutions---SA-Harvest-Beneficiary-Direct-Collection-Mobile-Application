namespace external_api_sim;

/// <summary>
/// Lets a demo or test make the simulator misbehave the way the real Foodspace might, so the backend's
/// retry handling can be shown without access to Foodspace. Toggled via the "Simulator only" endpoints.
/// </summary>
public class SimulatedFaults
{
    private volatile int _outageStatus; // 0 = healthy

    /// <summary>The HTTP status every Foodspace endpoint answers with while an outage is on, or null when healthy.</summary>
    public int? OutageStatus => _outageStatus == 0 ? null : _outageStatus;

    public void StartOutage(int status) => _outageStatus = status;

    public void EndOutage() => _outageStatus = 0;
}
