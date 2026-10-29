# Polar Terror Hunting

Special Rewards mode reads the "N left" counter from the reward details at most
once an hour. That read becomes an internal rally quota. Each rally this routine
confirms counts as one, and later rallies skip the reward panel until the quota
is spent or an hour has passed since the read. The next read replaces the quota
with the newly observed remainder.

A read of zero is exhaustion for the current UTC day. The next run is 30 minutes
after 00:00 UTC. Spending the internal quota is not exhaustion: the routine waits
until the hour has elapsed and reads the panel again, because the earlier read
can be stale. A failed read does not start the hour or spend the quota. Unlimited
mode never opens the reward details.

The next wake after a confirmed rally is the earliest recorded return. Return
time is the ticked Hold-a-rally preparation time, plus the deployment travel
time twice (march out and back), plus 5 seconds. When travel time cannot be
read, the slot is held for 10 minutes. If no march slot is idle, the wake is the
earlier of those recorded returns and the march-panel estimate. Stamina below
the configured reserve, with item use off, can push that wake out until stamina
regenerates.
