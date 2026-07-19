import os
from PIL import Image

def resize_icon(source_path, target_dir, sizes):
    if not os.path.exists(source_path):
        print(f"Source icon not found: {source_path}")
        return

    with Image.open(source_path) as img:
        # Ensure icon is square
        width, height = img.size
        if width != height:
            min_dim = min(width, height)
            img = img.crop((0, 0, min_dim, min_dim))

        for name, size in sizes.items():
            # Standard icons
            standard_dir = os.path.join(target_dir, f"mipmap-{name}")
            os.makedirs(standard_dir, exist_ok=True)
            img.resize((size, size), Image.Resampling.LANCZOS).save(os.path.join(standard_dir, "ic_launcher.png"))
            img.resize((size, size), Image.Resampling.LANCZOS).save(os.path.join(standard_dir, "ic_launcher_round.png"))
            
            # Foreground for adaptive icons
            img.resize((size, size), Image.Resampling.LANCZOS).save(os.path.join(standard_dir, "ic_launcher_foreground.png"))

if __name__ == "__main__":
    source = "/home/ubuntu/octave-streaming-android/resources/app_icon.png"
    target = "/home/ubuntu/octave-streaming-android/android/app/src/main/res"
    
    # Android mipmap sizes
    android_sizes = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192
    }
    
    resize_icon(source, target, android_sizes)
    print("Icons resized successfully.")
