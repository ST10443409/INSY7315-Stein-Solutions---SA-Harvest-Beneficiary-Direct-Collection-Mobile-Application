// A TCP proxy that makes a fast link behave like a 2G/3G one (#55): limited bandwidth each way, added latency, and a
// bounded buffer, like a phone's socket send buffer in front of a slow radio. Used because the Android emulator's own
// network profiles (-netspeed/-netdelay, `adb emu network speed`) are not enforced by emulator 37.x; see
// docs/performance/low-connectivity.md.
//
//   dotnet run docs/performance/throttle-proxy.cs -- gsm            # listens on :5001, forwards to localhost:5000
//   dotnet run docs/performance/throttle-proxy.cs -- edge 5001 localhost:5000
//
// The app then uses http://10.0.2.2:5001/ (the emulator's name for this machine). The profiles are the emulator's own
// numbers, so results compare with the emulator documentation: kbit/s each way, and a round-trip latency range.
using System.Net;
using System.Net.Sockets;
using System.Threading.Channels;

var profiles = new Dictionary<string, (double KbitUp, double KbitDown, int MinRttMs, int MaxRttMs)>
{
    ["gsm"] = (14.4, 14.4, 150, 550),
    ["gprs"] = (28.8, 57.6, 150, 550),
    ["edge"] = (473.6, 473.6, 80, 400),
    ["umts"] = (384, 384, 35, 200),
    ["hsdpa"] = (5760, 13980, 0, 0),
    ["full"] = (0, 0, 0, 0),
};

var name = args.Length > 0 ? args[0] : "gsm";
var listenPort = args.Length > 1 ? int.Parse(args[1]) : 5001;
var target = (args.Length > 2 ? args[2] : "localhost:5000").Split(':');
if (!profiles.TryGetValue(name, out var profile))
{
    Console.Error.WriteLine($"Unknown profile '{name}'. Known: {string.Join(", ", profiles.Keys)}");
    return 1;
}

const int ChunkBytes = 512;
const int BufferChunks = 128; // 64 KB in flight per direction, about what a phone's socket buffer holds

var listener = new TcpListener(IPAddress.Any, listenPort);
listener.Start();
Console.WriteLine($"throttle-proxy: :{listenPort} -> {target[0]}:{target[1]} as '{name}' " +
                  $"(up {profile.KbitUp} kbit/s, down {profile.KbitDown} kbit/s, rtt {profile.MinRttMs}-{profile.MaxRttMs} ms; 0 = unlimited)");

while (true)
{
    var client = await listener.AcceptTcpClientAsync();
    _ = Task.Run(async () =>
    {
        using var c = client;
        using var server = new TcpClient();
        try
        {
            await server.ConnectAsync(target[0], int.Parse(target[1]));
            client.NoDelay = server.NoDelay = true;
            using var cts = new CancellationTokenSource();
            var up = Pump(client.GetStream(), server.GetStream(), profile.KbitUp, profile.MinRttMs, profile.MaxRttMs, cts.Token);
            var down = Pump(server.GetStream(), client.GetStream(), profile.KbitDown, profile.MinRttMs, profile.MaxRttMs, cts.Token);
            await Task.WhenAny(up, down);
            cts.Cancel(); // one side closed: close the other, as a real connection would
        }
        catch (Exception e) when (e is IOException or SocketException or OperationCanceledException)
        {
        }
    });
}

// One direction: chunks are read as they come (until the buffer is full), each is held for half a random round trip,
// then written no faster than the bandwidth allows. Order is kept.
static async Task Pump(NetworkStream from, NetworkStream to, double kbit, int minRtt, int maxRtt, CancellationToken token)
{
    var bytesPerSecond = kbit * 1000 / 8;
    var queue = Channel.CreateBounded<(byte[] Data, long DueMs)>(BufferChunks);
    var clock = System.Diagnostics.Stopwatch.StartNew();

    var reader = Task.Run(async () =>
    {
        var buffer = new byte[ChunkBytes];
        long lastDue = 0;
        try
        {
            int read;
            while ((read = await from.ReadAsync(buffer, token)) > 0)
            {
                var due = Math.Max(lastDue, clock.ElapsedMilliseconds + Random.Shared.Next(minRtt, maxRtt + 1) / 2);
                lastDue = due;
                await queue.Writer.WriteAsync((buffer[..read], due), token);
            }
        }
        finally
        {
            queue.Writer.TryComplete();
        }
    }, token);

    await foreach (var (data, due) in queue.Reader.ReadAllAsync(token))
    {
        var wait = due - clock.ElapsedMilliseconds;
        if (wait > 0) await Task.Delay(TimeSpan.FromMilliseconds(wait), token);
        await to.WriteAsync(data, token);
        if (bytesPerSecond > 0) await Task.Delay(TimeSpan.FromSeconds(data.Length / bytesPerSecond), token);
    }
    await reader;
}
