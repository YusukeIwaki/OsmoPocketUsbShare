#!/bin/bash
# Video conversion evaluation for OsmoPocket USB Share (Android).
# Compares, on short samples from example_movies/:
#  (ref)  source scaled to 2560 long-edge with Lanczos (slim_videos.py reference picture)
#  (slim) FFmpeg hevc_videotoolbox + Lanczos + SlimLogic auto bitrate  (Mac stand-in for slim_videos.py)
#  (gl)   FFmpeg hevc_videotoolbox + bilinear + same bitrate          (isolates the GL scaling-filter difference)
# The real Android encoder (Pixel Tensor MediaCodec HEVC) is measured separately on-device:
# see evaluation/results.md (exported file pulled from the Pixel 10a and scored with the same SSIM setup).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p evaluation/out
SECONDS_SAMPLE=10

probe_fps() { ffprobe -v error -select_streams v:0 -show_entries stream=avg_frame_rate -of csv=p=0 "$1"; }

ssim_of() { # $1=distorted $2=source $3=start
  ffmpeg -y -v info -ss "$3" -i "$2" -i "$1" -filter_complex \
    "[0:v]trim=duration=${SECONDS_SAMPLE},setpts=PTS-STARTPTS,scale=2560:1440:flags=lanczos,format=yuv420p[ref];[1:v]trim=duration=${SECONDS_SAMPLE},setpts=PTS-STARTPTS,format=yuv420p[dist];[dist][ref]ssim" \
    -an -f null - 2>&1 | grep -o "All:[0-9.]*" | tail -1 | cut -d: -f2
}

for src in example_movies/DJI_20261107094517_0001_D.MP4 example_movies/DJI_20261107103824_0002_D.MP4 example_movies/DJI_20261107114219_0006_D.MP4; do
  name=$(basename "$src" .MP4)
  dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$src")
  start=$(python3 -c "print(max(0, float('$dur')/2 - ${SECONDS_SAMPLE}/2))")
  echo "== $name duration=${dur}s sample_start=${start}s"
  # slim stand-in: Lanczos, HEVC (VideoToolbox HW), 12 Mbps (SlimLogic auto for 2560x1440@29.97), AAC copy
  ffmpeg -y -v error -ss "$start" -i "$src" -t $SECONDS_SAMPLE -vf "scale=2560:1440:flags=lanczos" \
    -c:v hevc_videotoolbox -b:v 12M -maxrate 24M -bufsize 48M -pix_fmt p010le -profile:v main10 -tag:v hvc1 \
    -c:a copy "evaluation/out/${name}_slim.mp4"
  # GL stand-in: bilinear scaling, identical encoder settings
  ffmpeg -y -v error -ss "$start" -i "$src" -t $SECONDS_SAMPLE -vf "scale=2560:1440:flags=bilinear" \
    -c:v hevc_videotoolbox -b:v 12M -maxrate 24M -bufsize 48M -pix_fmt p010le -profile:v main10 -tag:v hvc1 \
    -c:a copy "evaluation/out/${name}_gl.mp4"
  echo "slim_ssim=$(ssim_of "evaluation/out/${name}_slim.mp4" "$src" "$start")"
  echo "gl_ssim=$(ssim_of "evaluation/out/${name}_gl.mp4" "$src" "$start")"
  ls -lh "evaluation/out/${name}_slim.mp4" "evaluation/out/${name}_gl.mp4"
done
echo "DONE"
