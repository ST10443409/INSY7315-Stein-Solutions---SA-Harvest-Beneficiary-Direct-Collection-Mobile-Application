using System.Text.RegularExpressions;
using api.Data;
using api.Models;

namespace api.Services;

/// <summary>
/// What the API accepts as a signature or photo, in one place (the endpoint and its tests both read it). Every rule exists because
/// the bytes come from a phone in the field and may be wrong, huge, or not a picture at all:
/// size caps per kind, only JPEG and PNG, and the type is read from the file's own first bytes, never from the header the client
/// sent (a header is a claim; the bytes are the evidence).
/// </summary>
public static class AttachmentRules
{
    /// <summary>A finger-drawn signature is a few KB; half a megabyte is generous.</summary>
    public const long MaxSignatureBytes = 512 * 1024;

    /// <summary>The app scales photos to 1600 px at JPEG 85 (a few hundred KB); 5 MB leaves room for a different phone.</summary>
    public const long MaxPhotoBytes = 5 * 1024 * 1024;

    /// <summary>The route's hard ceiling (anything over is refused before it is read, even after decompression).</summary>
    public const long MaxRequestBytes = MaxPhotoBytes + 64 * 1024;

    /// <summary>Photo slots are numbered from 0; more than this per collection is not a real donation.</summary>
    public const int MaxPhotoSlot = 19;

    public const string Jpeg = "image/jpeg";
    public const string Png = "image/png";

    // Ids come from the device and become part of a blob name, so they may only be plain id characters (UUIDs are).
    private static readonly Regex SafeId = new(@"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$", RegexOptions.Compiled | RegexOptions.CultureInvariant);

    public static bool IsSafeId(string? id) => id is not null && SafeId.IsMatch(id);

    public static long MaxBytes(AttachmentKind kind) =>
        kind is AttachmentKind.DonorSignature or AttachmentKind.CboSignature ? MaxSignatureBytes : MaxPhotoBytes;

    /// <summary>Parses the wire name (<c>DONOR_SIGNATURE</c>, any case). False when it is not one of the four.</summary>
    public static bool TryParseKind(string? text, out AttachmentKind kind)
    {
        kind = default;
        if (string.IsNullOrWhiteSpace(text)) return false;
        var wanted = text.Trim().ToUpperInvariant();
        foreach (var candidate in Enum.GetValues<AttachmentKind>())
        {
            if (EnumWire.Of(candidate) != wanted) continue;
            kind = candidate;
            return true;
        }
        return false;
    }

    /// <summary>The slot must be a real photo number for a photo, and 0 for every other kind.</summary>
    public static bool SlotIsValid(AttachmentKind kind, int slot) =>
        kind == AttachmentKind.Photo ? slot is >= 0 and <= MaxPhotoSlot : slot == 0;

    /// <summary>The media type of the declared <c>Content-Type</c> without parameters (<c>image/png; charset=x</c> becomes <c>image/png</c>), lower-cased; null when absent.</summary>
    public static string? MediaTypeOf(string? contentType)
    {
        if (string.IsNullOrWhiteSpace(contentType)) return null;
        var end = contentType.IndexOf(';');
        return (end < 0 ? contentType : contentType[..end]).Trim().ToLowerInvariant();
    }

    public static bool IsAllowedMediaType(string? mediaType) => mediaType is Jpeg or Png;

    /// <summary>What the bytes are, from their first bytes: <see cref="Jpeg"/>, <see cref="Png"/> or null for anything else.</summary>
    public static string? DetectImageType(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length >= 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF) return Jpeg;
        ReadOnlySpan<byte> png = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
        if (bytes.Length >= png.Length && bytes[..png.Length].SequenceEqual(png)) return Png;
        return null;
    }
}
