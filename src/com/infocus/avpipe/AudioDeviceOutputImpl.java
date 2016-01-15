/*
** Copyright (c) 2014  InFocus Corporation. All rights reserved.
*/
/*============================================================================
**
**  FILE        AudioDeviceOutputImpl.java
**
**  PURPOSE     Implement audio output using android AudioTrack
**
**==========================================================================*/

package com.infocus.avpipe;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import org.apache.http.util.ByteArrayBuffer;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Audio Decoder Implementation
 * <p>
 * This class is used to init & control audio play logic.It will be called in
 * JNI layer. Client should not use it directly.
 * </p>
 * 
 * @author Lucky
 */
public class AudioDeviceOutputImpl implements IAudioDevice
{
    private final String TAG = "AudioDeviceOutputImpl";
    private AudioFormatInfo mConfiguredFormat;
    private AudioTrack mAudioTrack;
    private long bytesWritten = 0;
    private final int audioStream = AudioManager.STREAM_VOICE_CALL;
    private final int mAudioOutputChannelConfig = AudioFormat.CHANNEL_OUT_MONO;
    private int mConfiguredBufferSize = 0;
    private boolean isPlay = false;

    /* debug */
    private boolean DEBUG_CAPTURE = false;
    private FileOutputStream debug_file;
    private String debug_filename = "/storage/external_storage/sdcard1/AudioDeviceOutputImpl-capture.pcm";

    /* NOTE that JNI MUST call setBufferSize at least to setup the AudioTrack information.  Based on getBufferSize()'s return. */
    @Override
    public int getBufferSize(AudioFormatInfo info) {
        printAudioFormatInfo(info);
        int bufferSize = AudioTrack.getMinBufferSize(info.getSampleRate(), mAudioOutputChannelConfig, info.getFormat());
        if (bufferSize < 0) {
            Log.v(TAG, "getBufferSize() -- Invalid buffering configuration!\n");
        }
        return bufferSize;
    }

    @Override
    public int setBufferSize(int bufferSize)
    {
        if (mAudioTrack != null) {
            Log.v(TAG, "Can't configure AudioTrack buffering requirements while the track is in use!\n\n");
            return -1;
        }
        mConfiguredBufferSize = bufferSize;
        Log.v(TAG, "Configured AudioTrack buffer size: " + mConfiguredBufferSize);
        return 0;
    }

    @Override
    public void open(AudioFormatInfo info)
    {
        // if noone called getbufferSize and setBufferSize create a default.
        int createSize = mConfiguredBufferSize;
        if (createSize == 0) {
            createSize = AudioTrack.getMinBufferSize(info.getSampleRate(), mAudioOutputChannelConfig, info.getFormat());
            mConfiguredBufferSize = createSize;
            Log.v(TAG, "AudioDeviceOutputImpl creating a default min buffer size: " + mConfiguredBufferSize);
        }
        mAudioTrack = new AudioTrack(audioStream, info.getSampleRate(), mAudioOutputChannelConfig, info.getFormat(), createSize, AudioTrack.MODE_STREAM);
        mAudioTrack.setPlaybackPositionUpdateListener(AVSync.Instance());

        Log.v(TAG, "new AudioTrack(" + audioStream + ", " + info.getSampleRate() + ", " + mAudioOutputChannelConfig + ", " 
                + info.getFormat() + ", " + mConfiguredBufferSize + ", " + AudioTrack.MODE_STREAM + ")");

        /* store configuration */
        mConfiguredFormat = info;
    }
    
    @Override
    public void start()
    {
        //AVSync.Instance().Enable();
        bytesWritten = 0;

        if (AVSync.Instance().IsEnabled()) {
            mAudioTrack.setPositionNotificationPeriod(mConfiguredFormat.getSampleRate()/30);
        }

        Thread startThread = new Thread(new Runnable() {
            @Override
            public void run() {
                mAudioTrack.play();
                isPlay = true;
            }
        });
        startThread.start();
    }
    
    /**
     * The JNI layer will call this method for sending audio data to here for
     * playing
     * 
     * @param buf
     *            data container
     * @return
     */
    public int write(ByteBuffer audioBuffer, long ts)
    {
        byte[] outData = new byte[audioBuffer.capacity()];
        audioBuffer.get(outData);

        if (isPlay) {
          mAudioTrack.write(outData, 0, outData.length);
          bytesWritten += outData.length;
          debugWriteFile(outData);
        }

        return outData.length;
    }

    /**
     * Get amount of audio in the audio track buffer.
     *
     * @return buffer level in units of ms
     */
    public long getBufferLevel()
    {
        if (!isPlay) {
          return 0;
        }

        int bytes_per_sample = mConfiguredFormat.getFormat() == AudioFormat.ENCODING_PCM_16BIT ? 2 : 1;
        long play_pos = mAudioTrack.getPlaybackHeadPosition() * 1000 / mConfiguredFormat.getSampleRate();
        long write_pos = bytesWritten * 1000 / (mConfiguredFormat.getSampleRate() * bytes_per_sample);
        return write_pos - play_pos;
    }

    @Override
    public void stop()
    {
        mAudioTrack.setPositionNotificationPeriod(0);
        AVSync.Instance().Disable();
        if (isPlay) {
          isPlay = false;
          mAudioTrack.pause();
          mAudioTrack.flush();
          mAudioTrack.stop();
        }
    }
    
    @Override
    public void close()
    {
        if (mAudioTrack != null) {
          mAudioTrack.release();
          mAudioTrack = null;
        }
    }
    
    @Override
    public DevInfo getDevInfo(int devId)
    {
        return null;
    }

    @Override
    public void setAudioMute(boolean isMute) {
        // TODO Auto-generated method stub
    }

    public void printAudioFormatInfo(AudioFormatInfo info) {
        Log.v(TAG, "\n\n----Audio Format Info\n\n");
        Log.v(TAG, info.toString());
    }

    private void debugWriteFile(byte[] data) {
        if (DEBUG_CAPTURE) {
            if (debug_file == null) {
                try {
                    debug_file = new FileOutputStream(debug_filename);
                    Log.v(TAG, "-----New FileOutputStream " + debug_filename);
                } catch (FileNotFoundException e) {
                    Log.v(TAG, "Could not open " + debug_filename);
                }
            }
            
            try {
                debug_file.write(data, 0, data.length);
                Log.v(TAG, "WROTE AUDIO OUTPUT: " + data.length);
            } catch (IOException e) {
                Log.v(TAG, "Could not write to " + debug_filename);
            }
        }
    }
}
