/*
** Copyright (c) 2014  InFocus Corporation. All rights reserved.
*/
/*============================================================================
**
**  FILE        AudioDeviceInputImpl.java
**
**  PURPOSE     Implement audio input using android AudioRecord
**
**==========================================================================*/

package com.infocus.avpipe;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import android.media.AudioRecord;

/**
 * Audio Encoder Implementation
 * <p>
 * This class is used to init & control audio record logic.It will be called in
 * JNI layer. Client should not use it directly.
 * </p>
 *
 * @author Lucky
 */
public class AudioDeviceInputImpl implements IAudioDevice {
    private String TAG = "AudioDeviceInputImpl";
    private boolean isMute = false;
    private int mConfiguredBufferSize = 0;
    private byte[] mBuffer = null;
    private AudioRecord mAudioRecord;
    private boolean isRecord = false;
    private int limitSize = 320;
    private int mReadSize = 0;
    private int mReadPos  = 0;
    private ByteBuffer emptyBuffer = ByteBuffer.allocate(limitSize);

    /* debug */
    private boolean DEBUG_CAPTURE = false;
    private FileOutputStream debug_file;
    private String debug_filename = "/storage/external_storage/sdcard1/AudioDeviceInputImpl-capture.pcm";

    /* NOTE that JNI MUST call setBufferSize at least to setup the AudioRecord information.  Based on getBufferSize()'s return. */
    @Override
    public int getBufferSize(AudioFormatInfo info) {
        int bufferSize = AudioRecord.getMinBufferSize(info.getSampleRate(),info.getChannel(), info.getFormat());
        bufferSize = (bufferSize / limitSize + 1) * limitSize;
        if (bufferSize < 0) {
            Log.v(TAG, "getBufferSize() -- Invalid buffering configuration!\n");
        }
        
        return bufferSize;
    }
 
    @Override
    public int setBufferSize(int bufferSize) {
        if (mAudioRecord != null) {
            Log.v(TAG, "Can't configure AudioRecord buffering requirements while the AudioRecord is in use!\n\n");
            return -1;
        }
        mConfiguredBufferSize = bufferSize;
        emptyBuffer = ByteBuffer.allocate(mConfiguredBufferSize);
        mBuffer = new byte[mConfiguredBufferSize];
        Log.v(TAG, "AudioDeviceInputImpl configured for buffers of size: " + mConfiguredBufferSize);
        return 0;
    }

    @Override
    public void open(AudioFormatInfo info) {
        isMute = false;

        // if noone called getbufferSize and setBufferSize create a default.
        int createSize = mConfiguredBufferSize;
        if (createSize == 0) {
            createSize = AudioRecord.getMinBufferSize(info.getSampleRate(),info.getChannel(), info.getFormat());
            createSize = (createSize / limitSize + 1) * limitSize;
            emptyBuffer = ByteBuffer.allocate(createSize);
            mBuffer = new byte[createSize];
            mConfiguredBufferSize = createSize;
        }

        Log.v(TAG, "new AudioRecord(source=" + info.getSource() +
								   ", samplerate=" + info.getSampleRate() + 
                                   ", channelcfg=" + info.getChannel() +  
                                   ", format=" + info.getFormat() + 
                                   ", buffersize=" + createSize + ")");

        mAudioRecord = new AudioRecord(info.getSource(), info.getSampleRate(), info.getChannel(), info.getFormat(), createSize);
    }

    @Override
    public void start() {

        Thread startThread = new Thread(new Runnable() {
            @Override
            public void run() {
                mAudioRecord.startRecording();
                isRecord = true;
            }
        });
        startThread.start();
        return;
    }

    /**
     * The JNI layer will call this method for recored audio data
     *
     * @param buf
     *            data container
     * @return
     */
    public int read(ByteBuffer buf) {
        int bytes_read = 0;

        while (isRecord && ((mReadSize == 0) || (AudioRecord.ERROR_INVALID_OPERATION == mReadSize)))
        {
            mReadSize = mAudioRecord.read(mBuffer, 0, mConfiguredBufferSize);
        }

        if (isMute || !isRecord) {
            buf.rewind();
            buf.put(emptyBuffer.array());
            return emptyBuffer.capacity();
        } else {
            if (mReadSize > limitSize) {
                buf.rewind();
                buf.put(mBuffer, mReadPos, limitSize);
                bytes_read = limitSize;
                mReadSize -= limitSize;
                mReadPos  += limitSize;
            } else {
                buf.rewind();
                buf.put(mBuffer, mReadPos, mReadSize);
                bytes_read = mReadSize;
                mReadSize  = 0;
                mReadPos   = 0;
            }
        }

        return bytes_read;
    }

    @Override
    public void stop() {
        isRecord = false;
        mAudioRecord.stop();
    }

    @Override
    public void close() {
        if (mAudioRecord != null) {
            mAudioRecord.release();
            mAudioRecord = null;
        }
    }

    @Override
    public DevInfo getDevInfo(int devId) {
        return null;
    }

    @Override
    public void setAudioMute(boolean isMute) {
        this.isMute = isMute;
    }

    public void printAudioFormatInfo(AudioFormatInfo info) {
        Log.v(TAG, "\n\n----AudioDeviceInputImpl Format \n");
        Log.v(TAG, info.toString());
    }

    private void debugWriteFile(byte[] data, int size) {
        if (DEBUG_CAPTURE) {
            if (debug_file == null) {
                try {
                    debug_file = new FileOutputStream(debug_filename);
                } catch (FileNotFoundException e) {
                    Log.v(TAG, "Could not open " + debug_filename);
                }
            }

            try {
                debug_file.write(data, 0, size);
                Log.v(TAG, "WROTE AUDIO INPUT DATA:" + size);
            } catch (IOException e) {
                Log.v(TAG, "Could not write to " + debug_filename);
            }
        }
    }
}
