/*
** Copyright (c) 2014  InFocus Corporation. All rights reserved.
*/
/*============================================================================
**
**  FILE        AVSync.java
**
**  PURPOSE     Implement sync between audio/video playback
**
**==========================================================================*/

package com.infocus.avpipe;

import android.media.AudioTrack;
import android.media.AudioTimestamp;
import android.util.Log;

public class AVSync implements AudioTrack.OnPlaybackPositionUpdateListener {
    private static AVSync instance = null;
    private long m_latency = 100;
    private long m_pts = 0;
    private boolean m_enable = false;
  
    public static AVSync Instance() {
        if (instance == null) {
            instance = new AVSync();
        }
        return instance;
    }
  
    public AVSync() {
    }

    public void Enable() { m_enable = true; }

    public void Disable() { m_enable = false; }

    public boolean IsEnabled() { return m_enable; }

    public synchronized long GetPTS() { return m_pts;  }

    public synchronized boolean Wait(long pts) {
        if (!m_enable || (pts <= 0)) {
            return true;
        }

        while (m_pts < (pts - m_latency)) {
            try {
                wait();
            } catch (Exception e) {
                Log.e("AVSync", "Wait: " + e.getMessage());
            }
        }
        return true;
    }
  
    // @override
    public synchronized void onPeriodicNotification(AudioTrack track) {
        if (!m_enable) {
            return;
        }

        m_pts = (track.getPlaybackHeadPosition() * 1000) / 8000;
        Log.i("PTS", "aud = " + m_pts);
        notify();
    }
  
    // @override
    public synchronized void onMarkerReached(AudioTrack track) {
    }
}
