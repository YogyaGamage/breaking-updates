#!/bin/bash

data_folder="/mnt/ssd2/breaking-updates-data-collection/data/benchmark"
output_dir="/mnt/hdd1/breaking-updates/benchmark"
folder_size_limit=$((200 * 1024 * 1024 * 1024))

mkdir -p "$output_dir"

image_names=()
folder_size=0
tar_counter=1

for json_file in "$data_folder"/*.json; do
    if [ -f "$json_file" ]; then
        breakingCommit=$(jq -r '.breakingCommit' "$json_file")

        image1="ghcr.io/chains-project/breaking-updates:${breakingCommit}-pre"
        image2="ghcr.io/chains-project/breaking-updates:${breakingCommit}-breaking"

        docker pull "$image1"
        docker pull "$image2"

        image_names+=("$image1" "$image2")

        # Calculate the total size of the Docker images
        docker_size=$(docker save "${image_names[@]}" | gzip -c | wc -c)
        folder_size=$((folder_size + docker_size))

        # Check if the folder size exceeds the limit
        if [ $folder_size -ge $folder_size_limit ]; then
            tar_filename="$output_dir/bump_${tar_counter}.tar.gz"
            docker save "${image_names[@]}" | gzip > "$tar_filename"
            tar_size=$(du -sh "$tar_filename" | awk '{print $1}')
            echo "Created tar file: ${tar_filename}, Size: $tar_size"
            ((tar_counter++))
            folder_size=0
            image_names=()
        fi

        echo "Processed $json_file"
    fi
done

# Create a final tar file with the remaining images
if [ ${#image_names[@]} -gt 0 ]; then
    tar_filename="$output_dir/bump_${tar_counter}.tar.gz"
    docker save "${image_names[@]}" | gzip > "$tar_filename"
    tar_size=$(du -sh "$tar_filename" | awk '{print $1}')
    echo "Created final tar file: $tar_filename, Size: $tar_size"
fi

echo "All Docker images have been processed."
