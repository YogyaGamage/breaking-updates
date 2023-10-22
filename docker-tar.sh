#!/bin/bash

data_folder="/mnt/ssd2/breaking-updates-data-collection/data/benchmark"

output_dir="bump-benchmark"

mkdir -p "$output_dir"

image_names=()

for json_file in "$data_folder"/*.json; do
    if [ -f "$json_file" ]; then
        breakingCommit=$(jq -r '.breakingCommit' "$json_file")

        image1="ghcr.io/chains-project/breaking-updates:$breakingCommit-pre"
        image2="ghcr.io/chains-project/breaking-updates:$breakingCommit-breaking"

        docker pull "$image1"
        docker pull "$image2"

        image_names+=("$image1" "$image2")

        echo "Processed $json_file"
    fi
done

docker save "${image_names[@]}" | gzip > "$output_dir/bump.tar.gz"

tar_size=$(du -sh "$output_dir/bump.tar.gz" | awk '{print $1}')

echo "Created a single tar file containing all Docker images: $output_dir/bump.tar.gz"
echo "Tar file size: $tar_size"
