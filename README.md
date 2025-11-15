# My SMS Forwarder

An Android application that automatically forwards incoming SMS messages based on customizable filters.

<img src="https://raw.githubusercontent.com/thewhiteninja/my-sms-forwarder/refs/heads/main/images/filters.jpg" style="width:32%; display:inline-block;"  alt=""/>
<img src="https://raw.githubusercontent.com/thewhiteninja/my-sms-forwarder/refs/heads/main/images/history.jpg" style="width:32%; display:inline-block;"  alt=""/>
<img src="https://raw.githubusercontent.com/thewhiteninja/my-sms-forwarder/refs/heads/main/images/logs.jpg" style="width:32%; display:inline-block;"  alt=""/>

## Features

- Create filters to forward SMS from specific phone numbers or sender names
- Automatic forwarding when matching SMS are received
- Enable/disable filters individually
- View forwarding history with timestamps
- Receive notifications for each forwarded message
- Persistent storage using Room database

## Requirements

- Android 8.0 (API 26) or higher
- Notification permissions (Android 13+)

## Permissions

The app requires several sensitive permissions:

- **RECEIVE_SMS**: To detect incoming messages
- **SEND_SMS**: To forward messages
- **READ_SMS**: To read message content
- **POST_NOTIFICATIONS**: To show forwarding notifications

All permissions must be granted for the app to function properly.

## Privacy

This app processes SMS messages locally on your device. No data is transmitted to external servers. All filtering and forwarding happens on-device only.

## License

See LICENSE file.