# AegisTitan (Minecraft 1.21.11 Paper plugin)

**Aegis Wall Shield** – `/getshield`
Raise it and a giant energy wall shaped like a shield appears where you look.
Blocks all damage and projectiles coming through it (arrows bounce back), nobody can
walk through it, it's unbreakable, and normal axes can't disable it.
`/shieldsize <width> <height>` or `/shieldsize small|medium|large|huge|massive`

**Titan Cleaver** – `/getaxe`
Netherite axe: Sharpness V, Efficiency V, Unbreaking III, Mending, Fire Aspect II.
Sneak + right click = Titan Slam: a colossal particle axe smashes the ground,
splits it open and sends out a shockwave. Any Aegis Wall near the impact is cut in
half and that player's shield is disabled for a few seconds.
`/axesize <1-10>` or `/axesize normal|big|giant|colossal|mountain` makes the axe,
the cut, the cracked ground and the shockwave bigger. At size 10 the blade splits
the ground in a ~118-block-long chasm that goes all the way down through mountains.
`/axecooldown <seconds>` changes the slam cooldown for everyone (0 = none).

Settings are in `plugins/AegisTitan/config.yml` (`/aegistitan reload`).

**Leviathan Trident** – `/gettrident`
Impaling V, Loyalty III, Channeling, Unbreaking III, Mending. Throw it to summon a
colossal particle trident and ride it to wherever you aimed. The landing kills
everything in the direct hit zone (health set to 1, then 30 hearts of damage),
punches three huge holes in a row where the prongs hit and sends out a shockwave. Only an Aegis
Wall can stop it: they smash together with a huge mid-air shockwave and spikes.
`/tridentsize <1-30>`, `/tridentcooldown <seconds>`.

## Building
Push this repo to GitHub → Actions tab → latest "Build AegisTitan" run →
download the `AegisTitan-plugin` artifact → unzip → put the .jar in `plugins/`.
