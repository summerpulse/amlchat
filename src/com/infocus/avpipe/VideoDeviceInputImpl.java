/*
** Copyright (c) 2014  InFocus Corporation. All rights reserved.
*/
/*============================================================================
**
**  FILE        VideoDeviceInputImpl.java
**
**  PURPOSE     Implement video input and encoding using android MediaCodec
**
**==========================================================================*/

package com.infocus.avpipe;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.StringBuilder;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.LinearLayout;

import com.infocus.avpipe.AVTypes.EncoderOptimizations;
import com.infocus.avpipe.EncodeSettings;
import com.infocus.avpipe.ReadRequest;

/**
 * encode
 * 
 * <p>
 */
public class VideoDeviceInputImpl implements IVideoDevice, VideoCapture.VideoEncodeSync {

    private static final String TAG = "VideoDeviceInputImpl";
    private final int MAX_ENCODED_FRAMES = 10;
    private static final int ENCODER_QUEUE_DEPTH = 1;
    private boolean isVideoMute = false;
    private boolean isRunning = true;
    private boolean isEncoderOutputStarted = false;
    private boolean isEncoderInputStarted = false;
    private MediaCodec mEncodeMediaCodec;
    private ByteBuffer[] mEncodeInputBuffer;
    private ByteBuffer[] mEncodeOutputBuffer;
    private BlockingQueue<ByteBuffer> UnencodedFramesQueue = new ArrayBlockingQueue<ByteBuffer>(2);
    private BlockingQueue<Integer> EncodedFrameIndexes = new ArrayBlockingQueue<Integer>(MAX_ENCODED_FRAMES);
    private BlockingQueue<MediaCodec.BufferInfo> EncodedFrameInfos = new ArrayBlockingQueue<MediaCodec.BufferInfo>(MAX_ENCODED_FRAMES);
    private ByteBuffer mSPS_PPS_Buffer;
    private LinearLayout mLinearLayout;
    private SurfaceView mShowVideoView;
    private VideoFormatInfo mVideoFormatInfo;
    private Thread mEncoderInputThread;
    private Thread mEncoderOutputThread;
    private FileOutputStream fos;
    private EncodeSettings mEncodeSettings;
    private IVideoDeviceCallback mVideoDeviceCb;
    private int mEncoderBitrateKbps = 0;
    private boolean mSoftEncode = false;
    private EncodedFrameListener mEncodedFrameListener;
    private EncoderOptimizations mEncoderOptimizations = new EncoderOptimizations();

    /* debug */
    private static final boolean PROFILE_VIDEO = false;
    private static final boolean DEBUG = false;
    private final String capture_filename = "/storage/external_storage/sdcard1/VideoDeviceInputImpl-capture.h264";

    public interface EncodedFrameListener {
        public void onEncodedFrame(MediaCodec.BufferInfo bufferInfo);
    }

    public void setEncodedFrameListener(EncodedFrameListener l) {
        mEncodedFrameListener = l;
    }

    public EncoderOptimizations getEncoderOptimizations() {
        return mEncoderOptimizations;
    }
    public void setEncoderOptimizations(EncoderOptimizations opts) {
        mEncoderOptimizations = opts;
    }

    public VideoDeviceInputImpl() {
        // ideally we wouldn't do any creation in a ctor but open is not called until
        // a call is started and that is too late to set defaults.  Consider moving this.
        mEncodeSettings = new EncodeSettings();
        mEncodeSettings.setDefaults(mEncoderOptimizations);
    }

    public void setShowView(SurfaceView showVideoView) {
        this.mShowVideoView = showVideoView;
    }

    /**
     * update preview position
     * 
     * @param info
     */
    public void updatePreviewInfo(VideoPreviewInfo info) {

    }

    public void setCallback(IVideoDeviceCallback cb) {
        mVideoDeviceCb = cb;
    }

    @Override
    public void open(VideoFormatInfo vfi, boolean useSoftCodec) {
        mVideoFormatInfo = vfi;
        mSoftEncode = useSoftCodec;

        if (DEBUG) {
            Log.d(TAG, "open");
            try {
                fos = new FileOutputStream(capture_filename);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }

        mVideoFormatInfo.setWidth((mVideoFormatInfo.getWidth() + 15) / 16 * 16);
        mVideoFormatInfo.setHeight((mVideoFormatInfo.getHeight() + 15) / 16 * 16);

        if (VideoCapture.Instance().isStarted()) {
            VideoCapture.Instance().stop();
        }
        VideoCapture.Instance().openCamera(true);
        VideoCapture.Instance().setEncodeSize(mVideoFormatInfo.getWidth(), mVideoFormatInfo.getHeight());
        VideoCapture.Instance().setTextureView(mShowVideoView);
        VideoCapture.Instance().setEncoderCallback(this);

        initEncodeMediaCodec();
    }

    @Override
    public void setVideoFormat(VideoFormatInfo vfi) { }

    @Override
    public void setVideoMute(boolean Mute) { this.isVideoMute = Mute; }

    @Override
    public void start() {
        if (DEBUG)
            Log.d(TAG, "start");

        Log.i(TAG, "mEncodeMediaCodec.start()");
        mEncodeMediaCodec.start();
        mEncodeInputBuffer = mEncodeMediaCodec.getInputBuffers();
        mEncodeOutputBuffer = mEncodeMediaCodec.getOutputBuffers();

        isRunning = true;

        /* if the bitrate was adjusted between call start and encoder start time adjust it now
         * to maintain mocha settings. */
        if (mVideoFormatInfo.getBitRate() != (mEncoderBitrateKbps * 1000)) {
            setBitrate(mEncoderBitrateKbps);
        }

        feedCameraFramesToEncoder();
        gatherEncodedFrames();

        VideoCapture.Instance().startCapture();
    }

    @Override
    public void stop() {
        if (DEBUG) {
            Log.i(TAG, "stop");
            if (fos != null) {
                try {
                    fos.flush();
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        isRunning = false;
        VideoCapture.Instance().stop();

        // interrupt the camera->encoder thread
        if (mEncoderInputThread != null && !mEncoderInputThread.isInterrupted() && mEncoderInputThread.isAlive()) {
            try {
                mEncoderInputThread.interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // interrupt encoder output thread
        if (mEncoderOutputThread != null && !mEncoderOutputThread.isInterrupted() && mEncoderOutputThread.isAlive()) {
            try {
                mEncoderOutputThread.interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (mEncodeMediaCodec != null) {
            while (true) {
                if (isEncoderInputStarted || isEncoderOutputStarted) {
                    continue;
                }

                releaseEncodedFrames();
                Log.i(TAG, "mEncodeMediaCodec.stop()");
                mEncodeMediaCodec.stop();
                break;
            }
        }
    }

    @Override
    public void close() {
        if (DEBUG) {
            Log.i(TAG, "close");
        }
        if (mEncodeMediaCodec != null) {
            Log.i(TAG, "mEncodeMediaCodec.release()");
            mEncodeMediaCodec.release();
            mEncodeMediaCodec = null;
        }
        mSPS_PPS_Buffer = null;
        UnencodedFramesQueue.clear();
    }

    @Override
    public void abort() { }

    @Override
    public int getDevNum() { return 0; }

    @Override
    public int getCurrentDev() { return 0; }

    @Override
    public DevInfo getDevInfo(int devId) { return null; }

    public View getShowView() { return mLinearLayout; }

    /**
     * init encode mediacodec
     */
    private void initEncodeMediaCodec() {
        try {
            if (mSoftEncode) {
              mEncodeMediaCodec = MediaCodec.createByCodecName("OMX.google.h264.encoder");
            } else {
              mEncodeMediaCodec = MediaCodec.createEncoderByType(mVideoFormatInfo.getMimeType());
            }
            MediaFormat format = MediaFormat.createVideoFormat(mVideoFormatInfo.getMimeType(), mVideoFormatInfo.getWidth(), mVideoFormatInfo.getHeight());
            format.setInteger(MediaFormat.KEY_FRAME_RATE, mVideoFormatInfo.getFrameRate());
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, mVideoFormatInfo.getIframeInterval());
            format.setInteger(MediaFormat.KEY_BIT_RATE, mVideoFormatInfo.getBitRate());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, mVideoFormatInfo.getEncodeColorFormat());

            // Tell the EncodeSettings class what MediaCodec to configure.
            mEncodeSettings.clearSession();
            mEncodeSettings.setEncoder(mEncodeMediaCodec);

            if (mEncoderOptimizations.infinite_iframe_enable) {
                mEncodeSettings.setStaticInfiniteIFrame(format, 0xffffffff);
            }
            if (mEncoderOptimizations.intrarefresh_enable) {
                mEncodeSettings.setStaticIntraRefresh(format, mEncoderOptimizations.intrarefresh_refresh_value, mEncoderOptimizations.intrarefresh_overlap_value);
            }
            if (mEncoderOptimizations.macroblock_slice_enable) {
                mEncodeSettings.setStaticSliceSpacing(format, mEncoderOptimizations.macroblock_slice_value);
            }
            if (mEncoderOptimizations.sps_pps_enable) {
                mEncodeSettings.setStaticPrependSPSPPS(format);
            }

            mEncodeMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            mEncodeSettings.setBitrate(mEncoderOptimizations.encoder_bitrate_value);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onVideoCaptureStarted() {
    }

    @Override
    public void onVideoCaptureFrame(byte[] data, long timestamp) {
        if (data == null)
          return;

        if (PROFILE_VIDEO) {
          Log.i("VideoProfile", "    Camera capture, Camera Timestamp = " + (timestamp / 1000000)
            + ", System time = " + (System.nanoTime() / 1000000));
        }

        if (mVideoDeviceCb != null) {
          mVideoDeviceCb.onFrameRecv(timestamp);
        }

        UnencodedFramesQueue.offer(ByteBuffer.wrap(data));
    }

    /**
    * Generates the presentation time for frame N, in microseconds.
    */
    private long computePresentationTime(int frameIndex) {
          return 132 + (long)frameIndex * 1000000 / mVideoFormatInfo.getFrameRate();
    }

    /**
     * start feeding caputured camera frames to the encoder (MediaCodec)
     */
    private void feedCameraFramesToEncoder() {
        mEncoderInputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                isEncoderInputStarted = true;
                int generateIndex = 0;
                int width = mVideoFormatInfo.getWidth();
                int height = mVideoFormatInfo.getHeight();
                int widthTimesHeight = width * height;
                int wh4 = widthTimesHeight / 4;
                final ByteBuffer blackFramebuffer = ByteBuffer.allocate(widthTimesHeight * 3 / 2);
                Arrays.fill(blackFramebuffer.array(), 0, widthTimesHeight, (byte) 0);
                Arrays.fill(blackFramebuffer.array(), widthTimesHeight, blackFramebuffer.capacity(), (byte) 128);

                while (isRunning) {
                    ByteBuffer UnencodedFrame = null;
                    try {
                        UnencodedFrame = UnencodedFramesQueue.take();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    if (UnencodedFrame == null) {
                      continue;
                    }

                    if (!isRunning)
                        break;

                    int index = -100;
                    int WaitMicroSeconds = 10000;
                    try {
                        index = mEncodeMediaCodec.dequeueInputBuffer(WaitMicroSeconds);
                        if ((index < 0) || (index >= ENCODER_QUEUE_DEPTH))
                            continue;
                    } catch (Exception e1) {
                        Log.e(TAG, "exception: " + e1.getMessage());
                        continue;
                    }

                    /* We have a frame and we are going to fill an input buffer on the media codec below this line */
                    mEncodeInputBuffer[index].clear();

                    if (isVideoMute) {
                        mEncodeInputBuffer[index].put(blackFramebuffer.array());
                    } else {
                        mEncodeInputBuffer[index].put(UnencodedFrame.array());
                    }

                    long ptsUsec = computePresentationTime(generateIndex++);

                    if (PROFILE_VIDEO) {
                        Log.i("VideoProfile", "    Encoder input, Encoder Timestamp = " + (ptsUsec / 1000)
                          + ", System time = " + (System.nanoTime() / 1000000));
                    }

                    if (mVideoDeviceCb != null) {
                      mVideoDeviceCb.onFrameIn(ptsUsec);
                    }

                    try {
                        mEncodeMediaCodec.queueInputBuffer(index, 0, UnencodedFrame.capacity(), ptsUsec, 0);
                    } catch (Exception e) {
                        Log.e(TAG, "mEncodeMediaCodec.queueInputBuffer:" + e.getMessage());
                    }
                }
                isEncoderInputStarted = false;
            }
        });
        mEncoderInputThread.start();
    }

    private void gatherEncodedFrames() {
        mEncoderOutputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                isEncoderOutputStarted = true;
                while (isRunning) {
                    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                    int index = -100;
                    int WaitMicroSeconds = 100000;
                    try {
                        index = mEncodeMediaCodec.dequeueOutputBuffer(bufferInfo, WaitMicroSeconds);
                    } catch (Exception e) {
                        Log.e(TAG, "dequeueOutputBuffer error " + e.getMessage());
                    }

                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        mEncodeOutputBuffer = mEncodeMediaCodec.getOutputBuffers();
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat format = mEncodeMediaCodec.getOutputFormat();
                        if (DEBUG) 
                            Log.i(TAG, "media format changed " + format);
                    } else if (index >= 0) {
                        if (PROFILE_VIDEO) {
                          Log.i("VideoProfile", "    Encoder output, Encoder Timestamp = " + (bufferInfo.presentationTimeUs / 1000)
                            + ", System time = " + (System.nanoTime() / 1000000));
                        }

                        if (mVideoDeviceCb != null) {
                          mVideoDeviceCb.onFrameOut(bufferInfo.presentationTimeUs);
                        }

                        queueEncodedFrame(index, bufferInfo);
                        writeEncodedFrameDebug(mEncodeOutputBuffer[index], bufferInfo.size);
                    }
                }
                isEncoderOutputStarted = false;
            }
        });
        mEncoderOutputThread.start();
    }

    private void writeEncodedFrameDebug(ByteBuffer frame, int size) {
        if (DEBUG) {
            try {
                if (frame.isDirect()) {
                    byte[] out = new byte[size];
                    frame.get(out);
                    fos.write(out);
                } else {
                    fos.write(frame.array());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void printSPSPPS(ByteBuffer buffer) {
        StringBuilder sb = new StringBuilder();
        for (byte b : buffer.array())
            sb.append(String.format("%02x ", b));
        Log.v(TAG, "SPS-PPS : " + sb.toString());
    }

    private void queueEncodedFrame(int index, MediaCodec.BufferInfo bufferInfo) {
        synchronized(this) {
            EncodedFrameIndexes.add(index);
            EncodedFrameInfos.add(bufferInfo);
            if (mEncodedFrameListener != null) {
                mEncodedFrameListener.onEncodedFrame(bufferInfo);
            }
        }
    }

    private void copyEncodedFrame(ByteBuffer dest, int index, MediaCodec.BufferInfo bufferInfo) {

        /* setup position we read from and limit of reads on the mediacodec buffer. */
        mEncodeOutputBuffer[index].position(bufferInfo.offset);
        mEncodeOutputBuffer[index].limit(bufferInfo.offset + bufferInfo.size);

        dest.clear();
        dest.limit(bufferInfo.size);

        /* if the encoder is inserting SPSPPS packets for us, just copy and return. */
        if (mEncodeSettings != null && mEncodeSettings.isSPSPPSEnabled()) {
            dest.put(mEncodeOutputBuffer[index]);
            mEncodeMediaCodec.releaseOutputBuffer(index, false);
            return;
        }

        /* if we have not caught our first sps-pps buffer, queue it and return */
        if (mSPS_PPS_Buffer == null) {
           // Log.v(TAG, "--insert first SPS_PPS packet");
            mSPS_PPS_Buffer = ByteBuffer.allocate(bufferInfo.size);
            mSPS_PPS_Buffer.put(mEncodeOutputBuffer[index]); 
            dest.put(mSPS_PPS_Buffer);
            mEncodeMediaCodec.releaseOutputBuffer(index, false);
            return;
        } 

        /* we may need to prepend sps-pps data to the current encoded frame. */
        if ((mEncodeOutputBuffer[index].get(4) & 0x0F) == 5) {
           // Log.v(TAG, "--drop in another SPS_PPS packet");
            dest.limit(mEncodeOutputBuffer[index].limit() + mSPS_PPS_Buffer.capacity());
            dest.put(mSPS_PPS_Buffer.array());
            dest.put(mEncodeOutputBuffer[index]);
            mEncodeMediaCodec.releaseOutputBuffer(index, false);
            return;
        } 

        // default, copy frame onward.
        dest.put(mEncodeOutputBuffer[index]);
        mEncodeMediaCodec.releaseOutputBuffer(index, false);
    }

    private void releaseEncodedFrames() {
        MediaCodec.BufferInfo info;
        Integer index;
        for (int i=0; i<MAX_ENCODED_FRAMES; ++i) {
            index = EncodedFrameIndexes.poll();
            info = EncodedFrameInfos.poll();
            if ((index != null) && (info != null)) {
                Log.v(TAG, "releaseEncodedFrames: release index " + index + " buffer " + info);
                mEncodeMediaCodec.releaseOutputBuffer(index, false);
            }
        }
    }

    /**
     * called by jni to pull encoded frames out of the encoder to send over the network.
     * 
     * @param buf
     * @return
     */
    public int read(ReadRequest request) {
        if (!isRunning)
            return 0;

        /* synch so that codec stop/starts won't affect a read mid state change. */
        synchronized(this) {
            MediaCodec.BufferInfo info = EncodedFrameInfos.poll();
            Integer index = EncodedFrameIndexes.poll();
            if ((info != null) && (index != null)) {
                if (mVideoDeviceCb != null) {
                  mVideoDeviceCb.onFrameConsumed(info.presentationTimeUs);
                }

                request.getBuffer().rewind();
                copyEncodedFrame(request.getBuffer(), index, info);
                return request.getBuffer().position();
            }
        }
        return 0;
    }

    /* used for testing */
    public MediaCodec.BufferInfo peek() {
        return EncodedFrameInfos.peek();
    }

   /**
    * Called from JNI when the encoder has been requested to generate a new IDR frame for the Decoder
    * across the connection.
    */
    private void requestIDRFrame() {
        if (mEncodeSettings != null) {
            mEncodeSettings.requestIDRFrame();
        }
    }

   /**
    * Called from JNI when the encoder has been requested a bitrate change to the encoder.
    */
    private void setBitrate(int bitrate) {
        mEncoderBitrateKbps = bitrate;
        if (isRunning && (mEncodeSettings != null)) {
            mEncodeSettings.setBitrate(mEncoderBitrateKbps);
        }
    }
}
