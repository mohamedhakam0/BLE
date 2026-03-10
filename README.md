# BLE Chat App

A comprehensive Android application demonstrating Bluetooth Low Energy (BLE) communication using modern Android development practices.

## Features

- **Dual Roles**: Supports both **Central (Client)** and **Peripheral (Server)** roles within the same application.
- **GATT Communication**: Full implementation of GATT services and characteristics for bidirectional text chat.
- **Extended Advertising (Coded PHY)**: Utilizes BLE 5.0 Extended Advertising and **LE Coded PHY** for significantly increased range (Long Range mode).
- **Jetpack Compose UI**: Built with a modern, reactive user interface.
- **Dynamic Permissions**: Handles all necessary Bluetooth (Scan, Connect, Advertise) and Location permissions for Android 12 (API 31) and above, as well as legacy versions.

## Technical Details

- **Service UUID**: `0000180F-0000-1000-8000-00805F9B34FB` (Battery Service used as a placeholder).
- **RX Characteristic**: `9a429160-290d-4113-8706-28274b43b8b1` (Supports Notifications).
- **TX Characteristic**: `8b7a6c5d-4e3f-2a1b-0c9d-8e7f6a5b4c3d` (Supports Write).
- **PHY**: LE Coded (S=8) for maximum range.

## How to Use

1. **Prerequisites**: Two Android devices supporting BLE (at least one must support Peripheral mode).
2. **Installation**: Install the app on both devices.
3. **Peripheral Setup**:
   - Open the app on the first device.
   - Select **Peripheral (Server)** mode.
   - Click **Start Advertising**.
4. **Central Setup**:
   - Open the app on the second device.
   - Select **Central (Client)** mode.
   - Click **Start Scan**.
   - Select the first device from the list once it appears.
5. **Chat**: Once connected, type a message and press **Send** to communicate between devices.

## Requirements

- **Min SDK**: 26 (for Extended Advertising support).
- **Target SDK**: 34+
- **Hardware**: BLE-capable hardware with Extended Advertising support recommended for full feature set.

## Project Structure

- `MainActivity.kt`: Contains the core BLE logic for both Central and Peripheral roles, plus the Compose UI.
- `BleAdvertiser.kt`: Handles the Extended Advertising (Coded PHY) broadcasting.
- `BleMessage.kt`: Data model for serializing messages over BLE.
- `BleIntegrationExamples.kt`: Guide for integrating these components.

---
Created as a professional BLE integration example.
