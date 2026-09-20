/*
 * What FluidSynth would have used libsndfile for.
 *
 * SF3 is SF2 with each sample stored as an Ogg Vorbis stream, and FluidSynth
 * decodes those through libsndfile. That is a reasonable choice for a desktop
 * distribution and a poor one here: it means building libsndfile, libogg and
 * libvorbis — three dependencies, three more chances of a symbol collision —
 * for a single codec call whose entire job is "these bytes are Ogg, give me
 * 16-bit mono PCM".
 *
 * stb_vorbis does that in one public-domain source file. So
 * `tools/patches/fluidsynth-sf3-without-libsndfile.patch` keeps the part that is
 * genuinely FluidSynth's — finding the compressed bytes inside the SoundFont,
 * under its own file callbacks and mutex — and calls out here for the decode.
 * The same split as `beatrix_glue.c` and `dexed_glue.cpp`: supply what the
 * excluded code would have defined, in a file of ours.
 *
 * One honest difference from libsndfile. FluidSynth asks libsndfile for
 * `SFC_SET_SCALE_FLOAT_INT_READ`, which rescales rather than clips when a
 * decoded sample exceeds full scale (FluidSynth issue #1380 — lossy Vorbis can
 * overshoot the peak of the PCM it was encoded from). stb_vorbis clamps at
 * +/-32767 instead. On a sample already mastered near full scale that is a
 * different flavour of the same one-sample overshoot; it is not a difference
 * anybody has reported hearing, but it IS a difference, and it belongs here in
 * writing rather than in nobody's memory.
 */

#include <stdlib.h>

#define STB_VORBIS_NO_PUSHDATA_API
#define STB_VORBIS_NO_STDIO

/* Every public stb_vorbis name, moved out of the way.
 *
 * sfizz already vendors stb_vorbis, inside external/st_audiofile, so a second
 * unprefixed copy is not an ODR hazard to be discovered later -- it is an
 * immediate `duplicate symbol` from the linker, which is how this was found.
 *
 * The same remedy as tools/patches/sfizz-tunings-namespace.patch: rename one
 * copy rather than hope the two agree. Binding to sfizz's copy instead would
 * have saved about 100 kB and coupled SF3 support to the sampler's private
 * vendoring, which is a worse trade -- this file would then break whenever
 * sfizz changed how it reads Ogg files.
 *
 * The list is generated from the public declarations in stb_vorbis.c rather
 * than curated, so nothing can be missed by hand.
 */
#define stb_vorbis_close bagosynths_stb_vorbis_close
#define stb_vorbis_comment bagosynths_stb_vorbis_comment
#define stb_vorbis_decode_filename bagosynths_stb_vorbis_decode_filename
#define stb_vorbis_decode_frame_pushdata bagosynths_stb_vorbis_decode_frame_pushdata
#define stb_vorbis_decode_memory bagosynths_stb_vorbis_decode_memory
#define stb_vorbis_float_size_test bagosynths_stb_vorbis_float_size_test
#define stb_vorbis_flush_pushdata bagosynths_stb_vorbis_flush_pushdata
#define stb_vorbis_get_comment bagosynths_stb_vorbis_get_comment
#define stb_vorbis_get_error bagosynths_stb_vorbis_get_error
#define stb_vorbis_get_file_offset bagosynths_stb_vorbis_get_file_offset
#define stb_vorbis_get_frame_ bagosynths_stb_vorbis_get_frame_
#define stb_vorbis_get_frame_float bagosynths_stb_vorbis_get_frame_float
#define stb_vorbis_get_frame_short bagosynths_stb_vorbis_get_frame_short
#define stb_vorbis_get_frame_short_interleaved bagosynths_stb_vorbis_get_frame_short_interleaved
#define stb_vorbis_get_info bagosynths_stb_vorbis_get_info
#define stb_vorbis_get_sample_offset bagosynths_stb_vorbis_get_sample_offset
#define stb_vorbis_get_samples_ bagosynths_stb_vorbis_get_samples_
#define stb_vorbis_get_samples_float bagosynths_stb_vorbis_get_samples_float
#define stb_vorbis_get_samples_float_interleaved bagosynths_stb_vorbis_get_samples_float_interleaved
#define stb_vorbis_get_samples_short bagosynths_stb_vorbis_get_samples_short
#define stb_vorbis_get_samples_short_interleaved bagosynths_stb_vorbis_get_samples_short_interleaved
#define stb_vorbis_info bagosynths_stb_vorbis_info
#define stb_vorbis_open_file bagosynths_stb_vorbis_open_file
#define stb_vorbis_open_file_section bagosynths_stb_vorbis_open_file_section
#define stb_vorbis_open_filename bagosynths_stb_vorbis_open_filename
#define stb_vorbis_open_memory bagosynths_stb_vorbis_open_memory
#define stb_vorbis_open_pushdata bagosynths_stb_vorbis_open_pushdata
#define stb_vorbis_seek bagosynths_stb_vorbis_seek
#define stb_vorbis_seek_ bagosynths_stb_vorbis_seek_
#define stb_vorbis_seek_frame bagosynths_stb_vorbis_seek_frame
#define stb_vorbis_seek_start bagosynths_stb_vorbis_seek_start
#define stb_vorbis_stream_length_in_samples bagosynths_stb_vorbis_stream_length_in_samples
#define stb_vorbis_stream_length_in_seconds bagosynths_stb_vorbis_stream_length_in_seconds

#include "stb_vorbis.c"

/*
 * Decode one Ogg Vorbis stream to interleaved 16-bit PCM.
 *
 * Returns frames decoded (0 for an empty stream), or -1 on error. On success
 * *out owns a buffer that bagosynths_ogg_free() releases, and *channels says how many
 * channels it holds — the caller rejects anything but mono, because that is all
 * the SF3 format stores.
 */
int bagosynths_ogg_decode_mono16(const void *ogg, int len, short **out, int *channels)
{
    short *pcm = NULL;
    int rate = 0;
    int frames;

    *out = NULL;
    *channels = 0;

    if (ogg == NULL || len <= 0)
    {
        return -1;
    }

    frames = stb_vorbis_decode_memory((const unsigned char *)ogg, len,
                                      channels, &rate, &pcm);

    if (frames < 0)
    {
        /* stb_vorbis frees whatever it allocated before failing. */
        return -1;
    }

    *out = pcm;
    return frames;
}

void bagosynths_ogg_free(short *pcm)
{
    free(pcm);   /* stb_vorbis allocates the result with malloc */
}
