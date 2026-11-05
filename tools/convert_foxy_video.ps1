# Rebuild the in-game Foxy Easter egg from the original MP4 in Downloads.
# Generates a 4 x 7 transparent atlas of 26 frames and a synchronized OGG.
# FFmpeg is a temporary tool. The executable and original MP4 are NOT in the mod.
$ErrorActionPreference = "Stop"
$base = Split-Path $PSScriptRoot -Parent
$src = Get-ChildItem (Join-Path $env:USERPROFILE "Downloads") -File -Filter "*Foxy Jumpscare*green screen*.mp4" |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -eq $src) { throw "The green-screen MP4 was not found in Downloads." }
$ffmpeg = Get-Command ffmpeg -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1
if (-not $ffmpeg) {
    $ffmpeg = Get-ChildItem (Join-Path $env:TEMP "milestone_foxy_tools\wheel_unpacked") -Recurse -File -Filter "*ffmpeg*.exe" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $ffmpeg) { throw "FFmpeg is not available." }
$tex = Join-Path $base "src\main\resources\assets\milestone\textures\gui\foxy_frames.png"
$soundDir = Join-Path $base "src\main\resources\assets\milestone\sounds"
New-Item -ItemType Directory -Force $soundDir | Out-Null
$audio = Join-Path $soundDir "foxy_jumpscare.ogg"
$frameDir = Join-Path $env:TEMP "milestone_foxy_tools\frames"
New-Item -ItemType Directory -Force $frameDir | Out-Null
Remove-Item (Join-Path $frameDir "frame_*.png") -Force -ErrorAction SilentlyContinue
& $ffmpeg -hide_banner -loglevel error -y -i $src.FullName -an -vf "scale=480:270:flags=lanczos,format=rgba" -fps_mode passthrough (Join-Path $frameDir "frame_%03d.png")
if ($LASTEXITCODE -ne 0) { throw "Frame extraction failed." }
$frames = @(Get-ChildItem $frameDir -Filter "frame_*.png" | Sort-Object Name)
if ($frames.Count -ne 26) { throw "Expected 26 frames, got $($frames.Count)." }

# The key is based on how much greener a pixel is than its R/B channels.
# Unlike a single chromakey colour, this also removes the dark-green matte
# and keeps warm/yellow eye highlights. Semi-transparent edges are de-spilled.
Add-Type -ReferencedAssemblies "System.Drawing.dll" -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public static class MilestoneFoxyAtlas {
    static void GreenKey(Bitmap bmp) {
        Rectangle r = new Rectangle(0, 0, bmp.Width, bmp.Height);
        BitmapData bd = bmp.LockBits(r, ImageLockMode.ReadWrite, PixelFormat.Format32bppArgb);
        try {
            byte[] pixels = new byte[bd.Stride * bd.Height];
            Marshal.Copy(bd.Scan0, pixels, 0, pixels.Length);
            for (int y = 0; y < bd.Height; y++) {
                for (int x = 0; x < bd.Width; x++) {
                    int p = y * bd.Stride + x * 4;
                    int blue = pixels[p], green = pixels[p + 1], red = pixels[p + 2];
                    int max = Math.Max(red, blue);
                    int excess = green - max;
                    if (green <= 30 || green <= max * 1.17) continue;
                    double amt = Math.Max(0, Math.Min(1, (excess - 17) / 55.0));
                    pixels[p + 3] = (byte)Math.Round(255 * (1 - amt));
                    pixels[p + 1] = (byte)Math.Min(green, max + 12);
                }
            }
            Marshal.Copy(pixels, 0, bd.Scan0, pixels.Length);
        } finally {
            bmp.UnlockBits(bd);
        }
    }

    public static void Build(string[] paths, string destination) {
        using (Bitmap sheet = new Bitmap(480 * 4, 270 * 7, PixelFormat.Format32bppArgb)) {
            using (Graphics g = Graphics.FromImage(sheet)) {
                g.Clear(Color.Transparent);
                for (int i = 0; i < paths.Length; i++) {
                    using (Bitmap raw = new Bitmap(paths[i]))
                    using (Bitmap frame = new Bitmap(480, 270, PixelFormat.Format32bppArgb)) {
                        using (Graphics tileG = Graphics.FromImage(frame))
                            tileG.DrawImage(raw, 0, 0, 480, 270);
                        GreenKey(frame);
                        g.DrawImage(frame, (i % 4) * 480, (i / 4) * 270, 480, 270);
                    }
                }
            }
            sheet.Save(destination, ImageFormat.Png);
        }
    }
}
'@
[MilestoneFoxyAtlas]::Build([string[]]$frames.FullName, $tex)
& $ffmpeg -hide_banner -loglevel error -y -i $src.FullName -vn -c:a libvorbis -q:a 4 -ac 2 $audio
if ($LASTEXITCODE -ne 0 -or !(Test-Path $audio)) { throw "Audio extraction failed." }
$bitmap = [System.Drawing.Bitmap]::FromFile($tex)
try {
    Write-Output ("Atlas: " + $bitmap.Width + " x " + $bitmap.Height +
            ", " + (Get-Item $tex).Length + " bytes")
    Write-Output ("Background alpha: " + $bitmap.GetPixel(6, 6).A)
} finally { $bitmap.Dispose() }
Write-Output ("Audio: " + (Get-Item $audio).Length + " bytes")
