#!/bin/bash
# Build wrapper script that properly configures Android SDK path from environment variable

# Set ANDROID_SDK_ROOT if not already set
if [ -z "$ANDROID_SDK_ROOT" ]; then
    if [ -z "$ANDROID_HOME" ]; then
        export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
    else
        export ANDROID_SDK_ROOT="$ANDROID_HOME"
    fi
fi

# Write local.properties with the SDK path
cat > local.properties << EOF
sdk.dir=$ANDROID_SDK_ROOT
EOF

# Run gradle with the provided arguments
./gradlew "$@"
