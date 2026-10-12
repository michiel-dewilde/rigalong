/* Glue between setBfree's engine sources and this application.
 *
 * setBfree is normally built either as a JACK program (src/main.c) or as an LV2
 * plugin (b_synth/lv2.c). Both of those provide three symbols that the engine
 * sources reference but do not define. We compile neither, so we provide them
 * here -- exactly as b_synth/lv2.c does, and for the same reason.
 *
 * This file is part of RigAlong and is GPL-3.0-or-later; the setBfree sources it
 * supports are GPL-2-or-later, which combines. See LICENSING.md.
 */

#include <string.h>
#include <strings.h>

#include "cfgParser.h"

/* Declared extern in setBfree's src/main.h and read by tonegen.c and vibrato.c.
 * It is a global rather than per-instance in upstream; we set it once, before
 * initialising anything, and never change it. Two organ instances at different
 * sample rates would be wrong -- but there is only ever one output device. */
double SampleRateD = 44100.0;

/* The config keys main.c would have handled. Ours are all engine-side, so every
 * key that belongs to a driver we do not have is silently accepted and dropped:
 * returning 1 means "handled", which stops the parser complaining about config
 * files written for the JACK build. */
int
mainConfig (ConfigContext* cfg)
{
	if (strcasecmp (cfg->name, "midi.driver") == 0) return 1;
	if (strcasecmp (cfg->name, "midi.port") == 0) return 1;
	if (strcasecmp (cfg->name, "jack.connect") == 0) return 1;
	if (strcasecmp (cfg->name, "jack.out.left") == 0) return 1;
	if (strcasecmp (cfg->name, "jack.out.right") == 0) return 1;
	return 0;
}

const ConfigDoc*
mainDoc ()
{
	return NULL;
}
