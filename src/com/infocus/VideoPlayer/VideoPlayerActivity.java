package com.infocus.VideoPlayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemProperties;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.ListIterator;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

import com.infocus.avpipe.AVTypes;
import com.infocus.avpipe.IVideoDevice;
import com.infocus.avpipe.MediaPlayerWrapper;
import com.infocus.avpipe.ReadRequest;
import com.infocus.avpipe.VideoCapture;
import com.infocus.avpipe.VideoDeviceInputImpl;
import com.infocus.avpipe.VideoDeviceOutputImpl;
import com.infocus.avpipe.VideoFormatInfo;
import com.infocus.VideoPlayer.EncoderOptimizationsDialog;

import android.app.Fragment;
import android.app.FragmentManager;

public class VideoPlayerActivity extends Activity implements
		VideoDeviceInputImpl.EncodedFrameListener
{
	private static final String TAG = "VideoPlayerActivity";
	private static final int BUFFER_SIZE = 512 * 1024;
	private static final int FRAME_THROUGHPUT_INTERVAL = 60;
	private static final int STATS_REFRESH_INTERVAL_MS = 2000;
	private static final boolean USE_SW_ENCODER = false;
	private static final boolean ENCODER_ONLY = false;

	private VideoFormatInfo mVideoFormatInfo;
	private VideoDeviceInputImpl mVideoInput;
	private VideoDeviceOutputImpl mVideoOutput;
	private Thread mVideoThread = null;
	private ByteBuffer mVideoBuffer;
	private boolean mIsStarted = false;
	private AlertDialog mVideoResDialog = null;
	private boolean mDebug = false;

	private boolean mEncoderCapturing = false;
	private final String mEncoderCaptureFilename = "/sdcard/encoder_capture.mpg";
	private FileOutputStream mEncoderCaptureStream;

	// resources
	private Button mStartStopButton;
	private Button mEncoderOptimizationsButton;
	private Button mEncoderCaptureButton;
	//private CheckBox mSwDecodeCheckBox;
	private SurfaceView mPreviewSfc;
	private SurfaceView mDecodeSfc;
	private SurfaceView mBGVideoView;
	private LinearLayout mStatsFrame;
	private Button mDebugLogBtn;
	private MediaPlayerWrapper mPlayerWrapper;

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		mVideoFormatInfo = new VideoFormatInfo();

		mVideoInput = new VideoDeviceInputImpl();
		mVideoInput.setCallback(new VideoCallback(true));

		if (!ENCODER_ONLY)
		{
			mVideoOutput = new VideoDeviceOutputImpl();
			mVideoOutput.setCallback(new VideoCallback(false));
		}

		mVideoBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

		// either or, one of these next routines will feed the decoder.
		mVideoInput.setEncodedFrameListener(this);
		// runVideoThread()

		loadResources();
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		mStartStopButton.setText("Start");
		updateStats();
	}

	@Override
	protected void onPause()
	{
		if (mIsStarted)
		{
			stopVideo();
		}
		if (mVideoResDialog != null)
		{
			mVideoResDialog.dismiss();
		}
		if(mPlayerWrapper!=null)
			mPlayerWrapper.stop();
		super.onPause();
	}

	@Override
	protected void onDestroy()
	{
		VideoCapture.Instance().closeCamera();
		super.onDestroy();
	}

	public VideoDeviceInputImpl getVideoInputDevice()
	{
		return mVideoInput;
	}

	private void loadResources()
	{
		mBGVideoView = (SurfaceView) findViewById(R.id.BGVideoSurface);
		mBGVideoView.getHolder().addCallback(new SurfaceHolder.Callback()
		{
			@Override
			public void surfaceCreated(SurfaceHolder holder)
			{
				Log.d(TAG, "mBGVideoView surfaceCreated");
			}

			@Override
			public void surfaceChanged(SurfaceHolder holder, int format,
					int width, int height)
			{
				// Log.d(TAG, "mDecodeSurface surfaceChanged");
				if (mPlayerWrapper != null)
				{
					// Log.d(TAG, "try to start mEncDecThread");
					mPlayerWrapper.stop();
				}
				// if (mResumed)
				// {
				// Log.d(TAG, "resuming, try to start a NEW mEncDecThread");
				String source = SystemProperties.get("media.demo.uri",
						"/sdcard/demo.mp4");
				Log.d(TAG, "MediaPlayerThread source=" + source);
				mPlayerWrapper = new MediaPlayerWrapper(mBGVideoView, source);
				mPlayerWrapper.start();
				// }
			}

			@Override
			public void surfaceDestroyed(SurfaceHolder holder)
			{
				Log.d(TAG, "mBGVideoView surfaceDestroyed");
			}
		});
		mStatsFrame = (LinearLayout) findViewById(R.id.frameStats);
		mDecodeSfc = (SurfaceView) findViewById(R.id.videoOutput);
		mPreviewSfc = (SurfaceView) findViewById(R.id.videoInput);
		//mSwDecodeCheckBox = (CheckBox) findViewById(R.id.checkSwDecode);

		mEncoderCaptureButton = (Button) findViewById(R.id.btnEncoderCapture);
		mEncoderCaptureButton.setText("Start Encoder Capture");
		mEncoderCaptureButton.setOnClickListener(new View.OnClickListener()
		{
			@Override
			public void onClick(View v)
			{
				mEncoderCapturing = !mEncoderCapturing;
				if (mEncoderCapturing)
				{
					mEncoderCaptureButton.setText("Capturing..");
					try
					{
						mEncoderCaptureStream = new FileOutputStream(
								mEncoderCaptureFilename);
					} catch (FileNotFoundException e)
					{
						e.printStackTrace();
					}
				} else
				{
					mEncoderCaptureButton.setText("Start Encoder Capture");
					String text = "Wrote log " + mEncoderCaptureFilename;
					try
					{
						mEncoderCaptureStream.flush();
						mEncoderCaptureStream.close();
					} catch (IOException e)
					{
						e.printStackTrace();
					}
					mEncoderCaptureStream = null;
					Toast.makeText(getApplicationContext(), text,
							Toast.LENGTH_SHORT).show();
				}
			}
		});

		mDebugLogBtn = (Button) findViewById(R.id.debugBtn);
		mDebugLogBtn.setText("Start Logging");
		mDebugLogBtn.setOnClickListener(new View.OnClickListener()
		{
			@Override
			public void onClick(View v)
			{
				mDebug = !mDebug;
				if (mDebug)
				{
					mDebugLogBtn.setText("Logging...");
					Debug.startMethodTracing("videoplayer");
				} else
				{
					mDebugLogBtn.setText("Start Logging");
					Debug.stopMethodTracing();
					String text = "Wrote log /sdcard/videoplayer";
					Toast.makeText(getApplicationContext(), text,
							Toast.LENGTH_SHORT).show();
				}
			}
		});

		mEncoderOptimizationsButton = (Button) findViewById(R.id.encoderOptimizationsButton);
		mEncoderOptimizationsButton
				.setOnClickListener(new View.OnClickListener()
				{
					@Override
					public void onClick(View v)
					{
						FragmentManager fm = getFragmentManager();
						EncoderOptimizationsDialog d = EncoderOptimizationsDialog
								.newInstance(1);
						d.show(fm, "dialog");
					}
				});

		mStartStopButton = (Button) findViewById(R.id.startStopBtn);
		mStartStopButton.setOnClickListener(new View.OnClickListener()
		{
			@Override
			public void onClick(View v)
			{
				if (mIsStarted)
				{
					mStartStopButton.setText("Start");
					//mSwDecodeCheckBox.setClickable(true);
					stopVideo();
				} else
				{
					mStartStopButton.setText("Stop");
					//mSwDecodeCheckBox.setClickable(false);
					showResolutionOptions();
				}
				updateStats();
			}
		});
	}

	public void onEncodedFrame(MediaCodec.BufferInfo info)
	{
		if (mVideoInput.read(new ReadRequest(mVideoBuffer)) > 0)
		{
			if (mEncoderCapturing && mEncoderCaptureStream != null)
			{
				mVideoBuffer.rewind();
				try
				{
					if (mVideoBuffer.isDirect())
					{
						byte[] out = new byte[mVideoBuffer.remaining()];
						mVideoBuffer.get(out);
						mEncoderCaptureStream.write(out);
					} else
					{
						mEncoderCaptureStream.write(mVideoBuffer.array());
					}
				} catch (IOException e)
				{
					e.printStackTrace();
				}
			}

			mVideoBuffer.rewind();
			mVideoOutput.write(mVideoBuffer, info.presentationTimeUs,
					((info.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0));
		}
	}

	private void runVideoThread()
	{
		if (mVideoThread == null)
		{
			mVideoThread = new Thread(new Runnable()
			{
				@Override
				public void run()
				{
					while (true)
					{
						MediaCodec.BufferInfo info = mVideoInput.peek();
						if (info != null)
						{
							if (mVideoInput.read(new ReadRequest(mVideoBuffer)) > 0)
							{
								if (!ENCODER_ONLY)
								{
									mVideoBuffer.rewind();
									mVideoOutput
											.write(mVideoBuffer,
													info.presentationTimeUs,
													((info.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0));
								}
							}
						}
					}
				}
			});
			mVideoThread.start();
		}
	}

	private void showResolutionOptions()
	{
		VideoCapture.Instance().openCamera(true);
		final AVTypes.VideoFmt[] fmts = VideoCapture.Instance()
				.getSupportedFormats();
		CharSequence[] videoResItems = new CharSequence[fmts.length];
		for (int i = 0; i < fmts.length; ++i)
		{
			videoResItems[i] = Integer.toString(fmts[i].width) + "x"
					+ Integer.toString(fmts[i].height);
		}

		// Creating and Building the Dialog
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle("Select Video Resolution");
		builder.setCancelable(false);
		builder.setSingleChoiceItems(videoResItems, -1,
				new DialogInterface.OnClickListener()
				{
					public void onClick(DialogInterface dialog, int item)
					{
						mVideoFormatInfo.setWidth(fmts[item].width);
						mVideoFormatInfo.setHeight(fmts[item].height);
						startVideo();
						mVideoResDialog.dismiss();
					}
				});
		mVideoResDialog = builder.create();
		mVideoResDialog.show();
	}

	private void startVideo()
	{
		mDecodeSfc.setVisibility(View.VISIBLE);
		mPreviewSfc.setVisibility(View.VISIBLE);

		startLatencyTracking();

		if (!ENCODER_ONLY)
		{
			mVideoOutput.setShowView(mDecodeSfc);
			mVideoOutput.open(mVideoFormatInfo, true/*mSwDecodeCheckBox.isChecked()*/);
			mVideoOutput.start();
		}

		mVideoInput.setShowView(mPreviewSfc);
		mVideoInput.open(mVideoFormatInfo, USE_SW_ENCODER);
		mVideoInput.start();

		mIsStarted = true;
	}

	private void stopVideo()
	{
		mIsStarted = false;

		mVideoInput.stop();
		mVideoInput.close();

		if (!ENCODER_ONLY)
		{
			mVideoOutput.stop();
			mVideoOutput.close();
		}

		mDecodeSfc.setVisibility(View.INVISIBLE);
		mPreviewSfc.setVisibility(View.INVISIBLE);
	}

	private void updateStats()
	{
		runOnUiThread(new Runnable()
		{
			public void run()
			{
				mStatsFrame.removeAllViews();
				if (mIsStarted)
				{
					String videoFormat = Integer.toString(mVideoFormatInfo
							.getWidth())
							+ "x"
							+ Integer.toString(mVideoFormatInfo.getHeight());

					addLineToStats("Video Info: " + videoFormat + " at "
							+ (mVideoFormatInfo.getBitRate() / 1000) + "kbps");
					addLineToStats("Time Statistics (camera / endoder / decoder / total):");
					addLineToStats("First Frame Delay = "
							+ Integer.toString(getStartupLatencyCam()) + "ms"
							+ " / "
							+ Integer.toString(getStartupLatencyCapture())
							+ "ms" + " / "
							+ Integer.toString(getStartupLatencyPlayback())
							+ "ms" + " / "
							+ Integer.toString(getStartupLatencyPlayback())
							+ "ms");
					addLineToStats("Average Latency = " + "NA" + " / "
							+ Integer.toString(getAverageLatencyCapture())
							+ "ms" + " / "
							+ Integer.toString(getAverageLatencyPlayback())
							+ "ms" + " / "
							+ Integer.toString(getAverageLatencyTotal()) + "ms");
					addLineToStats("Throughput = "
							+ Integer.toString(getThroughputCam()) + "fps"
							+ " / " + Integer.toString(getThroughputCapture())
							+ "fps" + " / "
							+ Integer.toString(getThroughputTotal()) + "fps"
							+ " / " + Integer.toString(getThroughputTotal())
							+ "fps");
				}
			}
		});
	}

	private void addLineToStats(String line)
	{
		TextView tv = new TextView(this);
		tv.setTextColor(Color.RED);
		tv.setText(line);
		mStatsFrame.addView(tv);
	}

	private class VideoTag
	{
		public long pts;
		public long nanoTimeStart;
		public long nanoTimeStartDec;
	}

	private static int mFrameCount = 0;
	private static int mFrameCountEnc = 0;
	private static int mFrameCountCam = 0;
	private static long mNanoTimeStart = 0;
	private static long mNanoTimeStartDec = 0;
	private static long mNanoTimeStartEnc = 0;
	private static long mNanoTimeStartCam = 0;
	private static long mStartLatencyDec = 0;
	private static long mStartLatencyEnc = 0;
	private static long mStartLatencyCam = 0;
	private static long mLatencyTotal = 0;
	private static long mLatencyDec = 0;
	private static long mLatencyEnc = 0;
	private static long mAverageLatencyTotal = 0;
	private static long mAverageLatencyDec = 0;
	private static long mAverageLatencyEnc = 0;
	private static int mThroughput = 0;
	private static int mThroughputEnc = 0;
	private static int mThroughputCam = 0;
	private static long mPreviousPTS = -1;
	private static long mPreviousPTSEnc = -1;
	private static long mPreviousPTSDec = -1;
	private static LinkedList<VideoTag> videoTagQ = new LinkedList<VideoTag>();

	private static void startLatencyTracking()
	{
		mFrameCount = 0;
		mFrameCountEnc = 0;
		mFrameCountCam = 0;
		mNanoTimeStart = System.nanoTime();
		mNanoTimeStartEnc = 0;
		mNanoTimeStartDec = 0;
		mNanoTimeStartCam = 0;
		mStartLatencyDec = 0;
		mStartLatencyEnc = 0;
		mStartLatencyCam = 0;
		mLatencyTotal = 0;
		mLatencyDec = 0;
		mLatencyEnc = 0;
		mAverageLatencyTotal = 0;
		mAverageLatencyDec = 0;
		mAverageLatencyEnc = 0;
		mThroughput = 0;
		mThroughputEnc = 0;
		mThroughputCam = 0;
		mPreviousPTS = -1;
		videoTagQ.clear();
	}

	private static int getAverageLatencyCapture()
	{
		return (int) (mAverageLatencyEnc / 1000000);
	}

	private static int getAverageLatencyPlayback()
	{
		return (int) (mAverageLatencyDec / 1000000);
	}

	private static int getAverageLatencyTotal()
	{
		return (int) (mAverageLatencyTotal / 1000000);
	}

	private static int getStartupLatencyCam()
	{
		return (int) (mStartLatencyCam / 1000000);
	}

	private static int getStartupLatencyCapture()
	{
		return (int) (mStartLatencyEnc / 1000000);
	}

	private static int getStartupLatencyPlayback()
	{
		return (int) (mStartLatencyDec / 1000000);
	}

	private static int getThroughputCam()
	{
		return mThroughputCam;
	}

	private static int getThroughputCapture()
	{
		return mThroughputEnc;
	}

	private static int getThroughputTotal()
	{
		return mThroughput;
	}

	private class VideoCallback implements IVideoDevice.IVideoDeviceCallback
	{

		private boolean mInput;

		public VideoCallback(boolean input)
		{
			mInput = input;
		}

		@Override
		public void onFrameRecv(long pts)
		{
			if (mInput)
			{
				long currTime = System.nanoTime();
				if (mNanoTimeStartCam == 0)
				{
					mNanoTimeStartCam = currTime;
				}
				updateCamThroughput(currTime);
			}
		}

		@Override
		public void onFrameIn(long pts)
		{
			long currTime = System.nanoTime();
			if (mInput)
			{
				if (mNanoTimeStartEnc == 0)
				{
					mNanoTimeStartEnc = currTime;
				}
				addNewTag(pts, currTime);
			} else
			{
				if (mNanoTimeStartDec == 0)
				{
					mNanoTimeStartDec = currTime;
				}
				setTagDecStartTime(pts, currTime);
			}
		}

		@Override
		public void onFrameOut(long pts)
		{
			long currTime = System.nanoTime();
			if (mInput)
			{
				if (isOutFrameValid(pts))
				{
					updateEncThroughput(currTime);
					updateEncLatency(pts, currTime);
				}
				if (ENCODER_ONLY)
				{
					removeTag(pts);
				}
			} else
			{
				if ((mPreviousPTS != pts) && (mPreviousPTS != (pts - 10)))
				{
					if (isOutFrameValid(pts))
					{
						updateDecThroughput(currTime);
						updateDecLatency(pts, currTime);
					}
					removeTag(pts);
				}
				mPreviousPTS = pts;
			}
		}

		@Override
		public void onFrameConsumed(long pts)
		{
		}

		private void addNewTag(long pts, long currTime)
		{
			synchronized (videoTagQ)
			{
				VideoTag tag = new VideoTag();
				tag.pts = pts;
				tag.nanoTimeStart = currTime;
				videoTagQ.add(tag);
			}
		}

		private void removeTag(long pts)
		{
			synchronized (videoTagQ)
			{
				while (!videoTagQ.isEmpty())
				{
					// ignore bogus frames on startup
					VideoTag tag = videoTagQ.peek();
					if (tag != null)
					{
						if (pts < (tag.pts - 10))
						{
							break;
						}
					}

					tag = videoTagQ.pop();
					if ((tag.pts == pts) || (tag.pts == (pts + 10)))
					{
						break;
					}
				}
			}
		}

		private boolean isOutFrameValid(long pts)
		{
			synchronized (videoTagQ)
			{
				ListIterator<VideoTag> it = videoTagQ.listIterator(0);
				while (it.hasNext())
				{
					VideoTag tag = it.next();
					if ((tag.pts == pts) || (tag.pts == (pts + 10)))
					{
						return true;
					}
				}
			}
			return false;
		}

		private void setTagDecStartTime(long pts, long currTime)
		{
			synchronized (videoTagQ)
			{
				ListIterator<VideoTag> it = videoTagQ.listIterator(0);
				while (it.hasNext())
				{
					VideoTag tag = it.next();
					if ((tag.pts == pts) || (tag.pts == (pts + 10)))
					{
						tag.nanoTimeStartDec = currTime;
						break;
					}
				}
			}
		}

		private void updateEncLatency(long pts, long currTime)
		{
			synchronized (videoTagQ)
			{
				ListIterator<VideoTag> it = videoTagQ.listIterator(0);
				while (it.hasNext())
				{
					VideoTag tag = it.next();
					if ((tag.pts == pts) || (tag.pts == (pts + 10)))
					{
						mLatencyEnc = mLatencyEnc
								+ (currTime - tag.nanoTimeStart);
						break;
					}
				}
			}
		}

		private void updateDecLatency(long pts, long currTime)
		{
			synchronized (videoTagQ)
			{
				ListIterator<VideoTag> it = videoTagQ.listIterator(0);
				while (it.hasNext())
				{
					VideoTag tag = it.next();
					if ((tag.pts == pts) || (tag.pts == (pts + 10)))
					{
						mLatencyTotal = mLatencyTotal
								+ (currTime - tag.nanoTimeStart);
						mLatencyDec = mLatencyDec
								+ (currTime - tag.nanoTimeStartDec);
						break;
					}
				}
			}
		}

		private void updateCamThroughput(long currTime)
		{
			if (mStartLatencyCam == 0)
			{
				mStartLatencyCam = currTime - mNanoTimeStart;
				updateStats();
			}
			if (++mFrameCountCam == FRAME_THROUGHPUT_INTERVAL)
			{

				int elapsedMS = (int) ((currTime - mNanoTimeStartCam) / 1000000);
				if (elapsedMS > 0)
				{
					mThroughputCam = (int) (mFrameCountCam * 1000 / elapsedMS);
				}

				mFrameCountCam = 0;
				mNanoTimeStartCam = currTime;

				updateStats();
			}
		}

		private void updateEncThroughput(long currTime)
		{
			if (mStartLatencyEnc == 0)
			{
				mStartLatencyEnc = currTime - mNanoTimeStart;
				updateStats();
			}
			if (++mFrameCountEnc == FRAME_THROUGHPUT_INTERVAL)
			{
				mAverageLatencyEnc = mLatencyEnc / mFrameCountEnc;

				int elapsedMS = (int) ((currTime - mNanoTimeStartEnc) / 1000000);
				if (elapsedMS > 0)
				{
					mThroughputEnc = (int) (mFrameCountEnc * 1000 / elapsedMS);
				}

				mFrameCountEnc = 0;
				mLatencyEnc = 0;
				mNanoTimeStartEnc = currTime;

				updateStats();
			}
		}

		private void updateDecThroughput(long currTime)
		{
			if (mStartLatencyDec == 0)
			{
				mStartLatencyDec = currTime - mNanoTimeStart;
				updateStats();
			}
			if (++mFrameCount == FRAME_THROUGHPUT_INTERVAL)
			{
				mAverageLatencyTotal = mLatencyTotal / mFrameCount;
				mAverageLatencyDec = mLatencyDec / mFrameCount;

				int elapsedMS = (int) ((currTime - mNanoTimeStartDec) / 1000000);
				if (elapsedMS > 0)
				{
					mThroughput = (int) (mFrameCount * 1000 / elapsedMS);
				}

				mFrameCount = 0;
				mLatencyTotal = 0;
				mLatencyDec = 0;
				mNanoTimeStartDec = currTime;

				updateStats();
			}
		}
	}
}