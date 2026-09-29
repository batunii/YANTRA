# Yantra — Privacy Policy

_Last updated: 29 September 2026_

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
| Images you add to a page | Copied into your workspace beside the page | Only to your own repository, if you turn on sync |
| A diagnostics log | A file in the app's own storage | Only if you choose to share it, from **Settings › Diagnostics** |

## Shared workspaces

A workspace can be a repository you share with other people on GitHub. What you write there is seen
by the people who have access to that repository — that is what sharing it means — and by nobody
else. Yantra does not publish anything, and there is no public feed.

- **Who added a task.** A task you add in a shared workspace records your GitHub username on its
  line (`by:yourname`), so the people you share with can see who wrote it — as the Android app does.
  Your personal workspace never records it.
- **Inviting a collaborator.** If you invite someone to a workspace, Yantra asks GitHub to invite
  their username to that repository. The invitation, and everything after it, is between you, them
  and GitHub.

## The diagnostics log

To help fix problems, Yantra keeps a short log on your device of what the app did: which screens
were opened, when syncs ran and whether they worked, and counts of changes. It holds **no task
content** — no titles, notes or drawings — only identifiers, counts, outcomes and GitHub's own error
messages. It never leaves the device unless you share it yourself from **Settings › Diagnostics**.

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

Yantra is a task app with no public social features, no advertising and no data collection. What
you write is shared only with the people you give access to your own repository. It is not directed
at children, and it collects no personal information from anyone.

## Changes

If this policy changes, the updated version will be published here and the date above will change.

## Contact

Questions about this policy: <sai@napkin.ie>
