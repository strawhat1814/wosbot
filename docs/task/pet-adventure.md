# Pet Adventure chests

The map shows at most three treasure pins. An idle pin is a coloured chest
with no pet portrait and no countdown. An occupied pin still shows the same
chest rim, plus a pet portrait and a dark timer pill under the marker.

`chestRed`, `chestPurple`, and `chestBlue` are rim crops, so they match both
idle and occupied pins. A start is only sent to a pin whose nearby countdown
template (`chestAdventureTimer`) is absent.

After Select Pet and Start, the accepted screen is the overlay whose subtitle
is `In Adventure` (`chestInAdventure`). That overlay with a full remaining
duration is a confirmed start, not an occupied-chest miss. The 2026-10-01
`start-outcome` frames are that overlay at 05:00:00 / 03:59:59 / 04:59:59.
A confirmed start spends 10 stamina. The visit then continues with every
other idle pin.

A pin that does not open Select, or whose Start tap does not show
`In Adventure`, is skipped for the rest of that visit. The other idle pins
are still started. The skipped pin is not tapped again until a later visit
re-reads the countdown pills. An unverified Start does not spend stamina.
Back out at most twice to the adventure map before the next pin; a further
back would leave Pet Adventure. The missing overlay can be the stamina
Obtain more sheet.

If at least one start was confirmed, the next visit is the 2 hour completion
check, and that visit may start pins that are still idle. If every attempted
pin was unverified, the next visit is a 15 minute retry. That retry is not
the completion wait and not the daily reset. Daily attempts exhausted still
uses the game reset.

A stored observe-only lock from the earlier unverified-start behavior is
cleared on the next visit.
