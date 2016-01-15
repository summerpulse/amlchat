/*
** Copyright (c) 2014  InFocus Corporation. All rights reserved.
*/
/*============================================================================
**
**  FILE        AVTypes.java
**
**  PURPOSE     Defines common AV types used for playback/capture
**
**==========================================================================*/

package com.infocus.avpipe;

/**
 * mocha client native methods
 * <p>
 */
public class AVTypes {

    static public class VideoFmt {
        public int width;
        public int height;
        public int framerate;
        public int bitrate;
    };

    static public class EncoderOptimizations {
        public boolean sps_pps_enable;
        public boolean infinite_iframe_enable;
        public boolean macroblock_slice_enable;
        public int macroblock_slice_value;
        public boolean encoder_bitrate_enable;
        public int encoder_bitrate_value;
        public boolean intrarefresh_enable;
        public int intrarefresh_refresh_value;
        public int intrarefresh_overlap_value;
    };
}
