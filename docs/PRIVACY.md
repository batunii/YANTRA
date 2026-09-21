# Yantra — Privacy Policy

_Last updated: 21 September 2026_

## The short version

**Yantra collects nothing.** There is no Yantra server, no account with us, no analytics, and no
third-party SDKs. Nothing you write is sent to us, because there is nowhere for it to go.

Your tasks, notes and drawings are plain Markdown files in a directory on your device. If you turn
on sync, those files are pushed to **a git repository you own**, using a token you granted, and the
traffic goes from your device to GitHub without passing through us.

## What is stored, and where

| What | Where it lives | Leaves the device? |
| --- | --- | --- |
| Tasks, lists, notes, events, ink | Plain files in the app's container | Only to your own git repository, if you turn on sync |
| Your GitHub access token | The system Keychain | Only to GitHub, to authenticate your own sync |
| Theme, accent, chosen calendars, zoom | App-group preferences | No |
| Focus sessions | A log file in your workspace | Only to your own repository, if you turn on sync |

## Your calendars

If you turn on **Show my calendars**, Yantra reads events from the calendars you tick, so it can
draw them beside your tasks.

- Yantra **only ever reads** your calendars. It never adds, changes or deletes an event.
- What it reads is used to draw the screen and is not stored anywhere by us.
- Which calendars you picked is remembered on that device only, never written to your repository —
  a calendar belongs to an account, not to a workspace.
- You can revoke access at any time in **Settings › Privacy & Security › Calendars**.

When you link a task to a meeting, Yantra writes a line in **your** repository holding the meeting's
identifier, a cached title and a time, so the link survives and so something still shows if the
calendar cannot be read. The meeting itself stays the property of the app that made it.

## GitHub

Sync is optional and off until you set it up.

- Yantra signs in through GitHub's own OAuth flow. We never see your GitHub password.
- The token is stored in the system Keychain and used only to read and write the repository you
  chose.
- You can disconnect the repository, or sign out entirely, in **Settings › GitHub**. Signing out
  discards the token from this device.
- To revoke Yantra's access permanently, remove it in your GitHub account under
  **Settings › Applications**.

## Deleting your data

There is no account to delete, because you never made one with us.

- Deleting the app removes every local file and the stored token.
- Your repository is yours: delete it on GitHub, or keep it and stop using the app.
- Signing out in the app removes the token from the device.

## Notifications

Reminders and the focus bell are scheduled locally by your device. Nothing is sent through a push
server, and no notification content leaves your phone.

## Children

Yantra is a task app with no social features, no user-to-user content, no advertising and no data
collection. It is not directed at children, and it collects no personal information from anyone.

## Changes

If this policy changes, the updated version will be published here and the date above will change.

## Contact

Questions about this policy: <sai@napkin.ie>
