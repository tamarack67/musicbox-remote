# Setting up a Raspberry Pi music server for the Music Box app

This guide turns a Raspberry Pi into a music server that the Music Box app controls over WiFi. The music lives on a drive attached to the Pi, and the Pi plays it through a DAC into your amplifier. The phone is only a remote, so no audio travels over the network. Allow about an hour, plus time for the library scan.

## What you need

| Item | Notes |
| --- | --- |
| Raspberry Pi 4 or 5 | 2GB RAM or more is plenty |
| DAC HAT or USB DAC | Avoid the Pi's own headphone jack, which is noisy. The Pi 5 has none |
| Music drive | SSD or hard drive, any filesystem (NTFS, exFAT, ext4). It is mounted read-only, so the Pi never changes your files |
| microSD card | 16GB or 32GB. It holds only the system |
| Power supply | Official Raspberry Pi supply: 5.1V 3A for a Pi 4, 27W for a Pi 5. A weak supply is the most common cause of problems |
| Windows, Mac or Linux computer | To write the SD card and run commands on the Pi |
| Home WiFi | The phone and the Pi must be on the same network |

## 1. Write the SD card and log in

1. Install Raspberry Pi Imager from raspberrypi.com/software.
2. Choose your Pi model, then **Raspberry Pi OS (other)** > **Raspberry Pi OS Lite (64-bit)**, then your SD card.
3. In the customisation steps, set:
    - Hostname: `musicbox`
    - A username and password, all lowercase username. Write them down
    - Keyboard layout: US for New Zealand keyboards
    - Your WiFi name and password, country NZ
    - SSH on, with password authentication
    - Raspberry Pi Connect off
4. Write the card, put it in the Pi, leave the music drive unplugged, and power up with a screen attached.
5. Wait 2 to 3 minutes. The screen shows `My IP address is 192.168.x.x` above the login prompt. Note that address.
6. On your computer, open a terminal (PowerShell on Windows) and connect:

```
ssh yourusername@192.168.x.x
```

Type `yes` at the fingerprint question, then your password. All later commands are typed here, one line at a time.

If the login is refused, try it on the Pi's own keyboard first. If that fails too, rewrite the card and retype the username and password carefully.

## 2. Install and configure

Paste each command on its own and press Enter. The first `sudo` asks for your password.

**Install the software.** `ntfs-3g` is only needed for NTFS drives; it does no harm otherwise.

```
sudo apt update && sudo apt install -y mpd mpc ntfs-3g
```

**Stop the first-boot setup from re-running.** On current Raspberry Pi OS it can later wipe the saved WiFi.

```
sudo touch /etc/cloud/cloud-init.disabled
```

**Keep the WiFi awake.**

```
printf "[connection]\nwifi.powersave = 2\n" | sudo tee /etc/NetworkManager/conf.d/wifi-powersave.conf
```

**Enable the DAC.** Find your DAC's overlay name in its maker's instructions. Many DACs built on the PCM5122 chip use `hifiberry-dacplus`. A USB DAC needs no overlay, so skip the second line.

```
sudo sed -i 's/^dtparam=audio=on/dtparam=audio=off/' /boot/firmware/config.txt
```

```
echo "dtoverlay=hifiberry-dacplus" | sudo tee -a /boot/firmware/config.txt
```

**Mount the music drive.** Plug it in and run `lsblk -f`. Note the UUID and FSTYPE of the partition holding your music. In the line below, replace the UUID, and replace `ntfs-3g` with `exfat` or `ext4` if that is what the drive uses.

```
sudo mkdir -p /mnt/music
```

```
echo "UUID=YOUR-UUID /mnt/music ntfs-3g ro,nofail,x-systemd.device-timeout=15 0 0" | sudo tee -a /etc/fstab
```

**Configure MPD.** Restart first so the DAC appears (`sudo reboot`, then reconnect), and run `aplay -l`. Your DAC's short card name follows `card 0:`, for example `sndrpihifiberry`. Put it in the two `CARD=` places below.

The mixer lines suit PCM5122-based DACs. For any other DAC, replace the three `mixer_` lines with `mixer_type "none"` and use the amp's volume knob.

```
sudo tee /etc/mpd.conf > /dev/null << 'EOF'
music_directory    "/mnt/music"
playlist_directory "/var/lib/mpd/playlists"
db_file            "/var/lib/mpd/tag_cache"
state_file         "/var/lib/mpd/state"
sticker_file       "/var/lib/mpd/sticker.sql"
user               "mpd"
bind_to_address    "any"
port               "6600"
auto_update        "no"
restore_paused     "yes"
zeroconf_enabled   "yes"
zeroconf_name      "Music Box"
filesystem_charset "UTF-8"

audio_output {
    type          "alsa"
    name          "DAC"
    device        "hw:CARD=sndrpihifiberry,DEV=0"
    mixer_type    "hardware"
    mixer_device  "hw:CARD=sndrpihifiberry"
    mixer_control "Digital"
}
EOF
```

**Start MPD at boot, then restart.**

```
sudo systemctl disable --now mpd.socket
```

```
sudo systemctl enable mpd.service
```

```
sudo reboot
```

## 3. Check, scan and connect

Reconnect after the restart and run these. `df` should show your drive's size, and `mpc status` should answer without an error.

```
aplay -l
```

```
df -h /mnt/music
```

```
vcgencmd get_throttled
```

```
mpc status
```

`throttled=0x0` means the power supply is coping. Anything else means it is too weak.

**Scan the library.** It runs in the background; a 1TB library took about 35 minutes. `mpc stats` shows the totals when it is done.

```
mpc update
```

**Set the volume.** With the amp turned right down, set the DAC to full output, then bring the amp up to your normal level. The DAC's control is logarithmic, so 40% is almost silent.

```
mpc volume 100
```

**Connect the app.** Install it on a phone on the same WiFi. It finds the Pi by name, or you can enter its address with port 6600 in Settings.

**Moving the Pi to another network.** Before you move it, add that network as a second saved connection. The Pi then joins whichever one is in range.

```
sudo nmcli --ask device wifi connect "OtherNetworkName"
```

## Troubleshooting

These are the problems we actually hit building the first box.

| Symptom | Cause and fix |
| --- | --- |
| Login refused after writing the card | Settings were not applied, or a symbol typed differently. Rewrite the card with a simple password and the US keyboard layout |
| SSH warns "remote host identification has changed" | The card was rewritten, so the Pi has a new identity. Run `ssh-keygen -R 192.168.x.x` on your computer and connect again |
| Pi won't start, or USB stops working, with the drive attached | Not enough power for the drive. Use the official supply. In an Argon ONE M.2 case, screw the base on fully so its spring pin contacts the Pi and powers the drive directly |
| WiFi drops while the drive is busy | A USB 3 drive interferes with 2.4GHz WiFi. Plug the drive into a black USB 2 port; it is still far faster than music needs |
| No IP address on the Pi's screen | The saved WiFi was lost. Run `sudo nmcli --ask device wifi connect "YourNetworkName"`, then the `cloud-init.disabled` command from step 2 |
| Music very quiet | MPD volume is below 100. Turn the amp down, then run `mpc volume 100` |
| `aplay -l` shows only HDMI | The DAC overlay line is missing or wrong in `/boot/firmware/config.txt` |
| Files missing from the app | Not because of missing tags: the app takes the artist, album, track number and title of untagged files from their folder and file names. Usually MPD hasn't scanned them yet, so run `mpc update`. Files in hidden or system folders (names starting with a dot, `$RECYCLE.BIN`, `System Volume Information`) are left out on purpose |
