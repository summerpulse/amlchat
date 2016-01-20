/*
** Copyright (c) 2014  InFocus Corporation. All rights reserved.
*/
/*============================================================================
**
**  FILE        VideoDeviceOutputImpl.java
**
**  PURPOSE     Implement video decoding and rendering using android MediaCodec
**
**==========================================================================*/

package com.infocus.avpipe;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.http.util.ByteArrayBuffer;

import com.infocus.VideoPlayer.VideoPlayerActivity;

import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceView;

/**
 * decode
 *
 * <p>
 */
public class VideoDeviceOutputImpl implements IVideoDevice {
    private static class DecodeTag {
      public DecodeTag() {
        timeFrame = 0;
        timeIn = 0;
        timeOut = 0;
      }

      public long timeFrame;
      public long timeIn;
      public long timeOut;
    }

    private static final String TAG = "VideoDeviceOutputImpl";
    private static final boolean DEBUG = false;
    private static final boolean PROFILE_VIDEO = false;
    private static final int DECODER_QUEUE_DEPTH = 1;

    private MediaCodec mDecodeMediaCodec;
    private SurfaceView mShowVideoView;
    private ByteBuffer[] mDecodeInputBuffers;
    private boolean isDecodeInit = false;
    private boolean isStop = true;
    private VideoFormatInfo mVideoFormatInfo;
    private BlockingQueue<DataBean> decodeInputQueue = new ArrayBlockingQueue<DataBean>(25);
    private LinkedList<DecodeTag> decodeTagQ = new LinkedList<DecodeTag>();
    private OnVideoFormatChange onVSizeChange;
    private Thread mDecoderThread;
    private boolean isDecodeStop = false;
    private FileOutputStream fos = null;
    private boolean mSoftDecode = false;
    private long mEnqueueTime = 0;
    private IVideoDeviceCallback mVideoDeviceCb = null;

    /* sps pps calculations */
    private static final short[] FRAME_START_TAG_SHORT = new short[] {0X00, 0X00, 0X00, 0X01};
    private static final byte[] FRAME_START_TAG_BYTE = new byte[] {0X00, 0X00, 0X00, 0X01};
    private boolean isInitSPS_PPS = false;

    public VideoDeviceOutputImpl() { }

    public void setVideoFormatCallback(OnVideoFormatChange callback) { this.onVSizeChange = callback; }

    public void setShowView(SurfaceView showVideoView) { this.mShowVideoView = showVideoView; }

    public void setCallback(IVideoDeviceCallback cb) { mVideoDeviceCb = cb; }

    public boolean isReady() { return true; }

    @Override
    public void open(VideoFormatInfo vfi, boolean useSoftCodec) {
        isDecodeStop = false;
        mVideoFormatInfo = vfi;
        mSoftDecode = useSoftCodec;

        if (DEBUG) {
            try {
                fos = new FileOutputStream("/sdcard/out.h264");
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * init decoder
     *
     * @param vfi
     * @param buffer
     *            the buffer is video sps/pss info
     */
    private void initDecodeMediaCodec(VideoFormatInfo vfi, ByteBuffer buffer) {
        /*
         * 00 00 00 01 27 42 40 1F 8B 95 02 83 F2 00 00 00 01 28 DE 03 18 80
         */
        // byte[] sps = { 0x00, 0x00, 0x00, 0x01, 0x27, 0x42, 0x40, 0x1F,
        // (byte) 0x8B, (byte) 0x95, 0x02, (byte) 0x83, (byte) 0xF2, 0x00,
        // 0x00, 0x00, 0x01, 0x28, (byte) 0xDE, 0x03, 0x18, (byte) 0x80 };
        ByteBuffer datBuffer = ByteBuffer.wrap(buffer.array());
//        if (mSoftDecode) {
//          mDecodeMediaCodec = MediaCodec.createByCodecName("OMX.google.h264.decoder");
//        } else {
          mDecodeMediaCodec = MediaCodec.createDecoderByType(vfi.getMimeType());
//        }
        VideoPlayerActivity.sCv.open();
        Log.d(TAG, "open sVc for others");
        MediaFormat format = MediaFormat.createVideoFormat(vfi.getMimeType(), vfi.getWidth(), vfi.getHeight());
        format.setByteBuffer("csd-0", datBuffer);
        if (DEBUG) {
            Log.d(TAG, "output surface available?" + mShowVideoView.getHolder());
        }

        if (mShowVideoView == null)
            return;

        mDecodeMediaCodec.configure(format, mShowVideoView.getHolder().getSurface(), null, 0);
        mDecodeMediaCodec.start();
        mDecodeInputBuffers = mDecodeMediaCodec.getInputBuffers();
        isDecodeInit = true;
        if (DEBUG)
            Log.i(TAG, "decode init finish");
    }

   
    @Override
    public void start() {
        if (DEBUG) {
            Log.d(TAG, "start");
        }
        mEnqueueTime = 0;
        isStop = false;
        startDecode();
    }

    @Override
    public void stop() {
        if (DEBUG) {
            Log.d(TAG, "stop");
            if (fos != null) {
                try {
                    fos.flush();
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        isStop = true;
        if (mDecoderThread != null)
            mDecoderThread.interrupt();
        if (mDecodeMediaCodec != null) {
            while (true) {
                if (!isDecodeStop) {
                    continue;
                }

                mDecodeMediaCodec.flush();
                mDecodeMediaCodec.stop();
                if (DEBUG)  
                    Log.i(TAG, "stop decode");

                mDecodeMediaCodec.release();
                mDecodeMediaCodec = null;
                break;
            }
        }
    }

    @Override
    public void close() {
        if (DEBUG) {
            Log.d(TAG, "close");
        }
        decodeInputQueue.clear();
        isDecodeInit = false;
    }

    /**
     * Used to receive h264 frame data<br/>
     * This method will be called from jni
     *
     * @param frameBuffer
     */
    public int write(ByteBuffer frameBuffer, long ts, boolean isSyncFrame) {
        if (mVideoDeviceCb != null) {
          mVideoDeviceCb.onFrameRecv(ts);
        }

        Log.d("Buffer", "capacity = " + frameBuffer.capacity() + ", remaining = " + frameBuffer.remaining());
        byte[] outData = new byte[frameBuffer.remaining()];
        frameBuffer.get(outData);
        if (!isInitSPS_PPS) {
            calculatePPS_SPS(outData, ts);
            return 0;
        }

        if (PROFILE_VIDEO) {
            long timeSinceLast = 0;
            if (mEnqueueTime > 0) {
              timeSinceLast = System.nanoTime() - mEnqueueTime;
            }

            String frameType = "";
            if (isSyncFrame) {
              frameType = "sync ";
            }

            Log.i("VideoProfile", "    Queue " + frameType + "frame for decode thread, Frame Timestamp = " + ts
              + ", System time = " + (System.nanoTime() / 1000000)
              + ", Time Since Last Frame = " + (timeSinceLast / 1000000));
        }

        mEnqueueTime = System.nanoTime();
        decodeInputQueue.offer(new DataBean(ByteBuffer.wrap(outData), ts, isSyncFrame));

        if (DEBUG) {
            try {
            fos.write(outData);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return 0;
    }
   
    /**
     * move left and add new at the end;
     *
     * @param tArray
     * @param t
     * @return
     */
    public short[] moveLeftWithNewEnd(short[] tArray, byte t) {
        short[] b = new short[tArray.length];
        for (int i = 0; i < b.length - 1; i++) {
            b[i] = tArray[i + 1];
        }
        if (t < 0) {
            b[b.length - 1] = (short) (t + 256);
        } else {
            b[b.length - 1] = t;
        }
        return b;
    }

    public synchronized void calculatePPS_SPS(byte[] testBuffer, long ts) {
        List<ByteArrayBuffer> bufferList = new ArrayList<ByteArrayBuffer>();

        // Checking for what?
        if ((testBuffer[4] & 0x0F) != 7)
            return;

        // cache the data per frame
        byte[] frameData = new byte[640 * 1024];
        int frameLength = 0;
        short[] temp = new short[] { 0, 0, 0, 0 };
        ByteArrayBuffer tmpBuffer;
        int spsppslength = 0;

        if (testBuffer.length < 4) {
            return;
        }
        for (int i = 4; i < testBuffer.length; i++) {
            temp = moveLeftWithNewEnd(temp, testBuffer[i]);
            frameData[frameLength++] = testBuffer[i];
            if (Arrays.equals(FRAME_START_TAG_SHORT, temp)) {
                tmpBuffer = new ByteArrayBuffer(frameLength);
                tmpBuffer.append(FRAME_START_TAG_BYTE, 0, 4);
                tmpBuffer.append(frameData, 0, frameLength - 4);
                bufferList.add(tmpBuffer);
                if (bufferList.size() == 2) {
                    if ((bufferList.get(1).byteAt(4) & 0x0F) != 8) {
                        return;
                    } else {
                        isInitSPS_PPS = true;
                        if (DEBUG) {
                            Log.d(TAG, "sps_pps is ok");
                        }
                        spsppslength = bufferList.get(0).length() + bufferList.get(1).length();
                        ByteBuffer buffer = ByteBuffer.allocate(spsppslength);
                        buffer.put(bufferList.get(0).buffer());
                        buffer.put(bufferList.get(1).buffer());
                        decodeInputQueue.add(new DataBean(buffer, ts, true));
                        break;
                    }
                }
                frameLength = 0;
            }
        }

        if (testBuffer.length > spsppslength) {
            ByteBuffer other = ByteBuffer.allocate(testBuffer.length - spsppslength);
            other.put(testBuffer, spsppslength, testBuffer.length - spsppslength);
            decodeInputQueue.add(new DataBean(other, ts, true));
        }
    }

    private DataBean recvInputData() {
        DataBean bean = null;
        try {
          bean = decodeInputQueue.poll(1, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.e(TAG, "Data queue poll error " + e.getMessage());
        }
        return bean;
    }

    private int decodeGetInputBuffer() {
        int inputBufferIndex = -1;
        try {
            inputBufferIndex = mDecodeMediaCodec.dequeueInputBuffer(1000);
        } catch (Exception e) {
            Log.e(TAG, "MediaCodec dequeInputBuffer error " + e.getMessage());
        }
        return inputBufferIndex;
    }

    private void decodeFillInputBuffer(ByteBuffer buf, int decodeBufferIndex) {
        ByteBuffer decodeInputBuf = mDecodeInputBuffers[decodeBufferIndex];
        decodeInputBuf.clear();
        decodeInputBuf.position(0);
        decodeInputBuf.put(buf.array());
    }

    private void decodeQueueInputBuffer(int decodeBufferIndex, int length, long ts) {
        if (mVideoDeviceCb != null) {
          mVideoDeviceCb.onFrameIn(ts);
        }
        mDecodeMediaCodec.queueInputBuffer(decodeBufferIndex, 0, length, ts, 0);
    }

    private boolean decodeSendInput(DataBean bean) {
        int decodeInputBufferIndex = decodeGetInputBuffer();
        if ((decodeInputBufferIndex >= 0) && (decodeInputBufferIndex < DECODER_QUEUE_DEPTH)) {

          decodeFillInputBuffer(bean.getBuffer(), decodeInputBufferIndex);
          decodeAddTag(bean.getTs());

          decodeQueueInputBuffer(decodeInputBufferIndex, bean.getBuffer().array().length, bean.getTs());
          bean = null;
          return true;
        }
        return false;
    }

    private int decodeRecvOutputBuffer(BufferInfo bufInfo) {
        int outputBufferIndex = -100;
        try {
            outputBufferIndex = mDecodeMediaCodec.dequeueOutputBuffer(bufInfo, 1000);
        } catch (Exception e) {
            Log.e(TAG, "dequeueOutputBuffer exception: " + e.getMessage());
        }

        if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat format = mDecodeMediaCodec.getOutputFormat();
            if (DEBUG) {
                Log.i(TAG, "media format change: " + format.getInteger(MediaFormat.KEY_WIDTH));
            }
            if (onVSizeChange != null) {
                onVSizeChange.onSizeChange(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT));
                onVideoFormatChange(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT));
            }
        } else if (outputBufferIndex < 0) {
        } else {
            return outputBufferIndex;
        }
        return -1;
    }

    private void decodeDisplayOutput(int decodeBufferIndex) {
        try {
          mDecodeMediaCodec.releaseOutputBuffer(decodeBufferIndex, true);
        } catch (Exception e) {
          Log.e(TAG, "MediaCodec releaseOutputBuffer error " + e.getMessage());
        }
    }

    private boolean decodeConsumeOutput() {
        if (!isDecodeInit) {
          return false;
        }

        BufferInfo decodeBufferInfo = new BufferInfo();
        int decodeOutputBufferIndex = decodeRecvOutputBuffer(decodeBufferInfo);
        if (decodeOutputBufferIndex < 0) {
          return false;
        }

        if (mVideoDeviceCb != null) {
          mVideoDeviceCb.onFrameOut(decodeBufferInfo.presentationTimeUs);
        }

        DecodeTag tag = decodeRemoveTag(decodeBufferInfo.presentationTimeUs);
        if (tag != null) {
          if (PROFILE_VIDEO) {
              Log.i("VideoProfile", "    Frame decoded, timestamp = " + tag.timeFrame
                + ", system time = " + tag.timeOut
                + ", decode duration = " + (tag.timeOut - tag.timeIn));
          }
        }

        decodeDisplayOutput(decodeOutputBufferIndex);

        if (mVideoDeviceCb != null) {
          mVideoDeviceCb.onFrameConsumed(decodeBufferInfo.presentationTimeUs);
        }

        return true;
    }

    private void decodeAddTag(long ts) {
        DecodeTag tag = new DecodeTag();
        tag.timeFrame = ts;
        tag.timeIn = System.nanoTime() / 1000000;
        decodeTagQ.add(tag);
    }

    private DecodeTag decodeRemoveTag(long ts) {
        while (!decodeTagQ.isEmpty()) {
          DecodeTag tag = decodeTagQ.pop();
          if (tag.timeFrame == ts) {
            tag.timeOut = System.nanoTime() / 1000000;
            return tag;
          }
          Log.i(TAG, "    Frame not decoded, ts = " + tag.timeFrame);
        }
        Log.e(TAG, "    Lost frame with ts = " + ts);
        return null;
    }

    private void startDecode() {
        mDecoderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                DataBean bean = null;
                int decodeInputBufferIndex = -1;
                while (!isStop && !mDecoderThread.isInterrupted()) {
                  if (bean == null) {
                    bean = recvInputData();
                  }

                  if (bean != null) {
                    if (!isDecodeInit) {
                        // check first buffer if sps/pps info
                        initDecodeMediaCodec(mVideoFormatInfo, bean.getBuffer());
                    }

                    if (decodeSendInput(bean)) {
                      bean = null;
                    }
                  }

                  decodeConsumeOutput();
                }
                if (DEBUG) {
                    Log.d(TAG, "startDecodeInput thread finish");
                }
                isDecodeStop = true;
                bean = null;
            }
        });
        mDecoderThread.start();
    }

    public static native int onVideoFormatChange(int w, int h);

    @Override
    public void setVideoMute(boolean isMute) { }

    @Override
    public void setVideoFormat(VideoFormatInfo vfi) { }

    @Override
    public void abort() { }

    @Override
    public int getDevNum() { return 0; }

    @Override
    public int getCurrentDev() { return 0; }

    @Override
    public DevInfo getDevInfo(int devId) { return null; }
}
