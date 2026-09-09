## Switching from the deprecated official version

Switching is easier than you may think!

- Preparation
  - In the official Syncthing app, go to the settings screen and export your config.
  - Now stop the official app entirely using the system app settings for Syncthing.
    -  Force stop the app basically. We need to ensure it's not running.
  - Confirm you can see the config export by using a file browser.
    - Default folder location: e.g. `/storage/emulated/0/backups/syncthing`
  - Create a new file called `config.zip` comprised of the following files:
    - cert.pem
    - https-cert.pem
    - https-key.pem
    - key.pem
    - config.xml
      - Tip: Use the [Material Files app](https://f-droid.org/packages/me.zhanghai.android.files/) to create the zip file.
  - You can remove the original *.pem and *.xml files after creating the `config.zip` file.
  - Noxfold-Sync will require the `config.zip` to restore your config.

- Migration
  - Install our [latest release](https://github.com/100pangci/syncthing-android/releases/latest) from GitHub or F-Droid.
  - Start Noxfold-Sync.
  - Complete the welcome slides.
  - In the Noxfold-Sync settings, go to "Import & Export".
    - Import the `config.zip` you created earlier.
      - 🍀 Don't worry. You will get an error message, but the import worked. That's because some Noxfold-Sync specific stuff wasn't found during the import and doesn't hurt.
    - 🔙 Hit "back" to go back to the "Import & Export" screen.
    - 🗝️ Set a password to protect future config exports.
    - Hit "Export config".
    - Hit "Import config".
    - 🔙 Hit "back" to go back to the app's main screen.
  - Like magic, everything should be as it was in Syncthing official.
    - You should see your shared folders and devices.
    - Confirm everything looks good.

- Cleanup
  - Uninstall the official Syncthing app.

- Further advice
  - 🐞 You won't be able to open Syncthing's web UI. Instead, you are presented a password prompt.
    - In the Noxfold-Sync settings, go to "Syncthing options".
    - Tap the "api key" entry to copy it to the clipboard.
    - Tap the "web UI password" entry and paste the previously copied "api key" as the password.
    - ✅ You have now manually fixed the problem and can enter the web UI again.
