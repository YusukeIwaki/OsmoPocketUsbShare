#!/bin/sh
# Regenerate all PNG launcher icons from design/app-icon.svg.
# Requires: rsvg-convert, ImageMagick (magick).
set -eu
cd "$(dirname "$0")/.."

MASTER=design/app-icon.svg
RES=app/src/main/res

# density -> size (px)
for spec in "mdpi 48" "hdpi 72" "xhdpi 96" "xxhdpi 144" "xxxhdpi 192"; do
  set -- $spec
  density=$1; size=$2
  out="$RES/mipmap-$density"
  rsvg-convert -w "$size" -h "$size" "$MASTER" -o "$out/ic_launcher.png"
  # round variant: circle mask with transparent corners
  magick "$out/ic_launcher.png" -alpha on \
    \( -size "${size}x${size}" xc:none -fill white \
       -draw "circle $((size/2)),$((size/2)) $((size/2)),2" \) \
    -compose DstIn -composite "$out/ic_launcher_round.png"
  echo "$out/ic_launcher.png ($size px)"
done

# Play Store / store listing icon, 512x512
rsvg-convert -w 512 -h 512 "$MASTER" -o app/src/main/ic_launcher-playstore.png
echo "app/src/main/ic_launcher-playstore.png (512 px)"

# Website favicon assets
rsvg-convert -w 512 -h 512 "$MASTER" -o website/public/app-icon-512.png
rsvg-convert -w 64 -h 64 "$MASTER" -o /tmp/favicon-64.png
magick /tmp/favicon-64.png website/public/favicon.ico
cp "$MASTER" website/public/favicon.svg
echo "website/public/favicon.svg, favicon.ico, app-icon-512.png"
