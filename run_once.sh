#!/bin/bash

# Exit immediately if a command exits with a non-zero status
set -e

# 1. Install System Prerequisites
sudo apt update
sudo apt install openjdk-17-jdk unzip wget -y

# 2. Setup Android SDK Command Line Tools
mkdir -p ~/Android/Sdk/cmdline-tools
cd ~/Android/Sdk/cmdline-tools

wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O tools.zip
unzip tools.zip
rm tools.zip
mv cmdline-tools latest

# 3. Configure Environment Paths
# Using grep checks to prevent appending duplicate entries if script is run multiple times
if ! grep -q "export ANDROID_HOME=" ~/.bashrc; then
    echo 'export ANDROID_HOME=$HOME/Android/Sdk' >> ~/.bashrc
    echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools' >> ~/.bashrc
fi

# Export paths globally for the remainder of this current execution block
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# 4. Accept Licenses and Initialize Platform Tools
yes | sdkmanager --licenses
sdkmanager "platform-tools"

# 5. Interactive Hardware Mapping (Universal udev rules setup)
echo "----------------------------------------------------------------"
echo "Scanning for connected USB devices to configure ADB permissions..."
echo "----------------------------------------------------------------"

mapfile -t DEVICES < <(lsusb | grep -Ei "android|phone|google|samsung|htc|huawei|xiaomi|motorola|lg|sony|oneplus|qualcomm|mtp|debug" || lsusb)

if [ ${#DEVICES[@]} -eq 0 ]; then
    echo "No obvious mobile devices found. Listing all system USB paths:"
    mapfile -t DEVICES < <(lsusb)
fi

for i in "${!DEVICES[@]}"; do
    echo "[$i] ${DEVICES[$i]}"
done
echo "----------------------------------------------------------------"

read -p "Select the target device number (0-$(( ${#DEVICES[@]} - 1 ))): " CHOICE

if [[ ! "$CHOICE" =~ ^[0-9]+$ ]] || [ "$CHOICE" -ge "${#DEVICES[@]}" ]; then
    echo "Invalid selection. Exiting installation workflow."
    exit 1
fi

SELECTED_LINE="${DEVICES[$CHOICE]}"
if [[ $SELECTED_LINE =~ ID\ ([0-9a-fA-F]{4}):([0-9a-fA-F]{4})\ (.*) ]]; then
    VENDOR_ID="${BASH_REMATCH[1]}"
    DEVICE_NAME="${BASH_REMATCH[3]}"
else
    echo "Failed to accurately parse target Hardware ID parameters."
    exit 1
fi

RULE_FILE="/etc/udev/rules.d/51-android.rules"

# Write out the rules profile to system directory
sudo touch "$RULE_FILE"
sudo chmod a+r "$RULE_FILE"

if grep -q "$VENDOR_ID" "$RULE_FILE"; then
    echo "Hardware profile path configuration rules for ID $VENDOR_ID already defined."
else
    sudo sh -c "echo '# $DEVICE_NAME' >> $RULE_FILE"
    sudo sh -c "echo 'SUBSYSTEM==\"usb\", ATTR{idVendor}==\"$VENDOR_ID\", MODE=\"0666\", GROUP=\"plugdev\"' >> $RULE_FILE"
    echo "Successfully generated system udev profile rules entry."
fi

# Reload the subsystem rules changes
sudo udevadm control --reload-rules
sudo service udev restart 2>/dev/null || sudo systemctl restart udev

# Safely cycle ADB tracking daemons
adb kill-server
adb start-server

echo "----------------------------------------------------------------"
echo "Hardware rule registration phase completed."
echo "CRITICAL: Check your phone screen now and accept the authorization prompt."
read -p "Press [Enter] once your device status reports as authorized..."
echo "----------------------------------------------------------------"

# 6. Build and Deploy Sequence
cd - > /dev/null # Jump back to original repository execution context block
./gradlew clean :app:compileDebugKotlin

# Strip existing signing structures to prevent signature mismatch collisions
echo "Purging conflicting legacy installation package references from the engine..."
adb uninstall com.aonozan.gimbalauto || true
adb uninstall --user 0 com.aonozan.gimbalauto || true

./gradlew installDebug