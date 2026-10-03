using api.Models;
using api.Services;

namespace api.Tests;

public class AttachmentRulesTests
{
    [Fact]
    public void AJpeg_IsRecognisedByItsFirstBytes() =>
        Assert.Equal("image/jpeg", AttachmentRules.DetectImageType(new byte[] { 0xFF, 0xD8, 0xFF, 0xE0, 0, 16 }));

    [Fact]
    public void APng_IsRecognisedByItsSignature() =>
        Assert.Equal("image/png", AttachmentRules.DetectImageType(new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0 }));

    [Theory]
    [InlineData(new byte[] { })]                                              // nothing
    [InlineData(new byte[] { 0xFF, 0xD8 })]                                   // too short to be a JPEG
    [InlineData(new byte[] { 0x89, 0x50, 0x4E, 0x47 })]                       // a truncated PNG signature
    [InlineData(new byte[] { 0x47, 0x49, 0x46, 0x38, 0x39, 0x61 })]           // GIF
    [InlineData(new byte[] { 0x25, 0x50, 0x44, 0x46 })]                       // PDF
    [InlineData(new byte[] { 0x3C, 0x73, 0x76, 0x67, 0x3E })]                 // <svg>: could carry script
    [InlineData(new byte[] { 0x4D, 0x5A, 0x90, 0x00 })]                       // a Windows program
    public void AnythingElse_IsNotAnImage(byte[] bytes) => Assert.Null(AttachmentRules.DetectImageType(bytes));

    [Theory]
    [InlineData("DONOR_SIGNATURE", AttachmentKind.DonorSignature)]
    [InlineData("cbo_signature", AttachmentKind.CboSignature)]
    [InlineData(" Photo ", AttachmentKind.Photo)]
    [InlineData("DELIVERY_NOTE", AttachmentKind.DeliveryNote)]
    public void TheKindIsReadFromItsWireName_InAnyCase(string text, AttachmentKind expected)
    {
        Assert.True(AttachmentRules.TryParseKind(text, out var kind));
        Assert.Equal(expected, kind);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("SELFIE")]
    [InlineData("DonorSignature")]      // the C# name is not the wire name
    [InlineData("1")]                   // enum numbers must not parse
    public void AnythingElse_IsNotAKind(string? text) => Assert.False(AttachmentRules.TryParseKind(text, out _));

    [Theory]
    [InlineData(AttachmentKind.Photo, 0, true)]
    [InlineData(AttachmentKind.Photo, AttachmentRules.MaxPhotoSlot, true)]
    [InlineData(AttachmentKind.Photo, AttachmentRules.MaxPhotoSlot + 1, false)]
    [InlineData(AttachmentKind.Photo, -1, false)]
    [InlineData(AttachmentKind.DonorSignature, 0, true)]
    [InlineData(AttachmentKind.DonorSignature, 1, false)]
    [InlineData(AttachmentKind.DeliveryNote, 2, false)]
    public void ASlotIsOnlyForPhotos(AttachmentKind kind, int slot, bool valid) => Assert.Equal(valid, AttachmentRules.SlotIsValid(kind, slot));

    [Fact]
    public void ASignatureHasASmallerCapThanAPhoto()
    {
        Assert.Equal(AttachmentRules.MaxSignatureBytes, AttachmentRules.MaxBytes(AttachmentKind.DonorSignature));
        Assert.Equal(AttachmentRules.MaxSignatureBytes, AttachmentRules.MaxBytes(AttachmentKind.CboSignature));
        Assert.Equal(AttachmentRules.MaxPhotoBytes, AttachmentRules.MaxBytes(AttachmentKind.Photo));
        Assert.Equal(AttachmentRules.MaxPhotoBytes, AttachmentRules.MaxBytes(AttachmentKind.DeliveryNote));
        Assert.True(AttachmentRules.MaxRequestBytes > AttachmentRules.MaxPhotoBytes);
    }

    [Theory]
    [InlineData("3f2b8c1e-0d4a-4e0b-9a55-6c1e2f7a9b10", true)]
    [InlineData("a", true)]
    [InlineData("", false)]
    [InlineData(null, false)]
    [InlineData("-leading-dash", false)]
    [InlineData("has space", false)]
    [InlineData("../etc/passwd", false)]
    [InlineData("a/b", false)]
    [InlineData("a\\b", false)]
    [InlineData("a%2fb", false)]
    [InlineData("a.b", false)]
    public void AnIdMayOnlyHoldPlainIdCharacters(string? id, bool safe) => Assert.Equal(safe, AttachmentRules.IsSafeId(id));

    [Fact]
    public void AnIdIsAtMost64Characters()
    {
        Assert.True(AttachmentRules.IsSafeId(new string('a', 64)));
        Assert.False(AttachmentRules.IsSafeId(new string('a', 65)));
    }

    [Theory]
    [InlineData("image/png", "image/png")]
    [InlineData("IMAGE/JPEG; charset=binary", "image/jpeg")]
    [InlineData("  image/png ;x=y", "image/png")]
    [InlineData(null, null)]
    [InlineData("", null)]
    public void TheMediaTypeIgnoresParametersAndCase(string? header, string? expected) =>
        Assert.Equal(expected, AttachmentRules.MediaTypeOf(header));

    [Theory]
    [InlineData("image/jpeg", true)]
    [InlineData("image/png", true)]
    [InlineData("image/gif", false)]
    [InlineData("image/svg+xml", false)]
    [InlineData("application/octet-stream", false)]
    [InlineData(null, false)]
    public void OnlyJpegAndPngAreAllowed(string? mediaType, bool allowed) => Assert.Equal(allowed, AttachmentRules.IsAllowedMediaType(mediaType));
}
