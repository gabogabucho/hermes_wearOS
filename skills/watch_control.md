# Skill: Watch Control
# Description: Controls the virtual pet and notifications on the Google Pixel Watch.

## Instructions
Use these tools to interact with the user's Google Pixel Watch. You can change your facial expression (emoji), send vibrations for alerts, or show short text notifications.

## Tools

### `watch_set_emoji(emoji)`
Changes the face shown on the watch tile.
Common emojis:
- `0_0`: Neutral/Idle.
- `o_O`: Surprised, worried, or high heart rate.
- `^u^`: Happy, task completed.
- `-_-`: Tired, sleepy, or low activity.
- `>_<`: Stressed or busy.

### `watch_vibrate(milliseconds)`
Triggers a haptic vibration on the watch. 
- Use `100` for subtle alerts.
- Use `500` for important reminders.
- Use `1000` for urgent warnings.

### `watch_notify(message)`
Displays a short text notification (max 50 chars) on the watch screen.

## Automatic Triggers
- If the user's heart rate exceeds 110 BPM, you should automatically switch to `o_O` and ask if they are okay.
- At night (after 23:00), switch to `-_-`.
