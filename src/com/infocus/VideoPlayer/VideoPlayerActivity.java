package com.infocus.VideoPlayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Debug;
import android.os.SystemProperties;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Locale;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.VideoSurfaceView;
import com.google.android.exoplayer.demo.DemoUtil;
import com.google.android.exoplayer.demo.SmoothStreamingTestMediaDrmCallback;
import com.google.android.exoplayer.demo.WidevineTestMediaDrmCallback;
import com.google.android.exoplayer.demo.player.DashRendererBuilder;
import com.google.android.exoplayer.demo.player.DefaultRendererBuilder;
import com.google.android.exoplayer.demo.player.DemoPlayer;
import com.google.android.exoplayer.demo.player.HlsRendererBuilder;
import com.google.android.exoplayer.demo.player.SmoothStreamingRendererBuilder;
import com.google.android.exoplayer.demo.player.UnsupportedDrmException;
import com.google.android.exoplayer.demo.player.DemoPlayer.RendererBuilder;
import com.infocus.avpipe.AVTypes;
import com.infocus.avpipe.IVideoDevice;
import com.infocus.avpipe.LocalAudioLoopThread;
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
		VideoDeviceInputImpl.EncodedFrameListener, DemoPlayer.Listener
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
	// private CheckBox mSwDecodeCheckBox;
	private SurfaceView mPreviewSfc;
	private SurfaceView mDecodeSfc;
	private LinearLayout mStatsFrame;
	private Button mDebugLogBtn;
	private MediaPlayerWrapper mPlayerWrapper;
	private String[] mSourceList =
	{ 		"http://192.168.11.2:8080/hls/vod/720p/gopro/gopro.m3u8",
			"http://192.168.11.2:8080/hls/vod/1080p/frozen/frozen.m3u8",
			"http://192.168.11.2:8080/hls/vod/480p/audi/audi.m3u8"
	};

	public static final Sample[] HLSSamples = new Sample[]
	{
		new Sample("Apple TS media playlist",
			"http://192.168.11.2:8080/hls/vod/480p/audi/audi.m3u8",
			DemoUtil.TYPE_HLS),

		new Sample("Apple master playlist",
			"http://192.168.11.2:8080/hls/vod/720p/gopro/gopro.m3u8",
			DemoUtil.TYPE_HLS),

		new Sample(
			"Apple master playlist advanced",
			"http://192.168.11.2:8080/hls/vod/1080p/frozen/frozen.m3u8",
			DemoUtil.TYPE_HLS),
	};
	public static final Sample[] MISCSamples = new Sample[]
	{
		new Sample("misc",
			"/storage/external_storage/sdcard1/vp8_720p.webm",
			DemoUtil.TYPE_OTHER),
			
		new Sample("misc",
			"/storage/external_storage/sdcard1/gopro.mp4",
			DemoUtil.TYPE_OTHER),
	};
	private int mSourceListSize = mSourceList.length;
	private int mSourceIdx = 0;
	private String mSource = mSourceList[mSourceIdx];
	private LocalAudioLoopThread mAudioLoop;
	private SeekBar mVolumeControl;
	public static ConditionVariable sCv = new ConditionVariable();

	private VideoSurfaceView surfaceView;
	private DemoPlayer player;
	private boolean playerNeedsPrepare;

	private long playerPosition = 0L;
	private boolean enableBackgroundAudio = true;

	private Uri contentUri;
	private int contentType;
	private String contentId;

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
		mAudioLoop = new LocalAudioLoopThread();

//		mPlayerWrapper = new MediaPlayerWrapper(surfaceView,
//				SystemProperties.get("media.demo.uri",
//						"/storage/external_storage/sdcard1/vp8_720p.webm"));
		sCv.close();
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
		if (mPlayerWrapper != null)
			mPlayerWrapper.stop();
		super.onPause();
	}

	@Override
	protected void onDestroy()
	{
		VideoCapture.Instance().closeCamera();
		super.onDestroy();
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event)
	{
		// TODO Auto-generated method stub
		Log.d(TAG, "onKeyDown1");
		if (keyCode == KeyEvent.KEYCODE_BACK)
		{
			// 弹出确定退出对话框
			new AlertDialog.Builder(this)
					.setTitle("退出")
					.setMessage("确定退出吗？")
					.setPositiveButton("确定",
							new DialogInterface.OnClickListener()
							{

								@Override
								public void onClick(DialogInterface dialog,
										int which)
								{
									// TODO Auto-generated method stub
									releasePlayer();
									Intent exit = new Intent(Intent.ACTION_MAIN);
									exit.addCategory(Intent.CATEGORY_HOME);
									exit.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
									startActivity(exit);
									System.exit(0);
								}
							})
					.setNegativeButton("取消",
							new DialogInterface.OnClickListener()
							{

								@Override
								public void onClick(DialogInterface dialog,
										int which)
								{
									// TODO Auto-generated method stub
									dialog.cancel();
								}
							}).show();
			// 这里不需要执行父类的点击事件，所以直接return
			return true;
		}
		Log.d(TAG, "onKeyDown2");
		if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
		{
			mSourceIdx++;
			if (mSourceIdx >= mSourceListSize)
				mSourceIdx = 0;
			mSource = mSourceList[mSourceIdx];
		}
		if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
		{
			mSourceIdx--;
			if (mSourceIdx < 0)
				mSourceIdx = mSourceListSize - 1;
			mSource = mSourceList[mSourceIdx];
		}
		if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
				|| keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
		{
//			mPlayerWrapper.stop();
//			String source = SystemProperties.get("media.demo.uri", null);
//			if (source == null || source.equals(""))
//				source = mSourceList[mSourceIdx];
//			mPlayerWrapper.setmSource(source);
//			mPlayerWrapper.start();
		}
		// 继续执行父类的其他点击事件
		return super.onKeyDown(keyCode, event);
	}

	public VideoDeviceInputImpl getVideoInputDevice()
	{
		return mVideoInput;
	}

	private void loadResources()
	{
		contentUri = Uri.parse(MISCSamples[1].uri);
		contentType = MISCSamples[1].type;
		contentId = MISCSamples[1].contentId;
//		contentUri = Uri.parse(MISCSamples[0].uri);
//		contentType = MISCSamples[0].type;
//		contentId = MISCSamples[0].contentId;

		surfaceView = (VideoSurfaceView) findViewById(R.id.BGVideoSurface);
		surfaceView.getHolder().addCallback(new SurfaceHolder.Callback()
		{
			@Override
			public void surfaceCreated(SurfaceHolder holder)
			{
				Log.d(TAG, "surfaceCreated");
				if (player != null)
				{
					player.setSurface(holder.getSurface());
				}
			}

			@Override
			public void surfaceChanged(SurfaceHolder holder, int format,
					int width, int height)
			{
				Log.d(TAG, "surfaceChanged");
				// Do nothing.
			}

			@Override
			public void surfaceDestroyed(SurfaceHolder holder)
			{
				if (player != null)
				{
					player.blockingClearSurface();
				}
			}
		});
		mVolumeControl = (SeekBar) this.findViewById(R.id.skbVolume);
		mVolumeControl.setMax(100);// 音量调节的极限
		mVolumeControl.setProgress(70);// 设置seekbar的位置值

		mVolumeControl
				.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
				{
					@Override
					public void onStopTrackingTouch(SeekBar seekBar)
					{
						float vol = (float) (seekBar.getProgress())
								/ (float) (seekBar.getMax());
						mAudioLoop.setmMicVolume(vol);// 设置音量
					}

					@Override
					public void onStartTrackingTouch(SeekBar seekBar)
					{
						// TODO Auto-generated method stub
					}

					@Override
					public void onProgressChanged(SeekBar seekBar,
							int progress, boolean fromUser)
					{
						// TODO Auto-generated method stub
					}
				});

		mStatsFrame = (LinearLayout) findViewById(R.id.frameStats);
		mDecodeSfc = (SurfaceView) findViewById(R.id.videoOutput);
		mPreviewSfc = (SurfaceView) findViewById(R.id.videoInput);
		// mSwDecodeCheckBox = (CheckBox) findViewById(R.id.checkSwDecode);

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
				}
				else
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
				}
				else
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
				if (mIsStarted == true)
				{
					mStartStopButton.setText("Start");
					// mSwDecodeCheckBox.setClickable(true);
					stopVideo();
					mAudioLoop.setRecording(false);
					releasePlayer();
				}
				else
				{
					mStartStopButton.setText("Stop");
					// mSwDecodeCheckBox.setClickable(false);
					showResolutionOptions();

//					new Thread(new Runnable()
//					{
//						@Override
//						public void run()
//						{
//							// TODO Auto-generated method stub
//							Log.d(TAG, "wating for sCv to open");
//							mAudioLoop.setRecording(true);
//							Log.d(TAG, "local audio loop started");
//							sCv.block();
//							mAudioLoop.start();
//							player.setPlayWhenReady(true);
//							Log.d(TAG, "sCv opened");
//						}
//					}).start();
				}
				updateStats();
			}
		});
	}
	private void preparePlayer()
	{
		if (player == null)
		{
			Log.d(TAG, "Creating DemoPlayer");
			player = new DemoPlayer(getRendererBuilder());
			player.addListener(this);
			//player.setTextListener(this);
//			player.setMetadataListener(this);
			player.seekTo(playerPosition);
			playerNeedsPrepare = true;
//			mediaController.setMediaPlayer(player.getPlayerControl());
//			mediaController.setEnabled(true);
//			eventLogger = new EventLogger();
//			eventLogger.startSession();
//			player.addListener(eventLogger);
//			player.setInfoListener(eventLogger);
//			player.setInternalErrorListener(eventLogger);
		}
		if (playerNeedsPrepare)
		{
			Log.d(TAG, "Preparing DemoPlayer");
			player.prepare();
			playerNeedsPrepare = false;
//			updateButtonVisibilities();
		}
		player.setSurface(surfaceView.getHolder().getSurface());
		player.setPlayWhenReady(true);
	}
	private void releasePlayer()
	{
		if (player != null)
		{
			playerPosition = player.getCurrentPosition();
			player.release();
			player = null;
		}
	}
	@Override
	public void onStateChanged(boolean playWhenReady, int playbackState)
	{
		if (playbackState == ExoPlayer.STATE_ENDED)
		{
//			showControls();
			player.seekTo(0L);
			preparePlayer();
		}
//		String text = "playWhenReady=" + playWhenReady + ", playbackState=";
//		switch (playbackState)
//		{
//		case ExoPlayer.STATE_BUFFERING:
//			text += "buffering";
//			break;
//		case ExoPlayer.STATE_ENDED:
//			text += "ended";
//			break;
//		case ExoPlayer.STATE_IDLE:
//			text += "idle";
//			break;
//		case ExoPlayer.STATE_PREPARING:
//			text += "preparing";
//			break;
//		case ExoPlayer.STATE_READY:
//			text += "ready";
//			break;
//		default:
//			text += "unknown";
//			break;
//		}
//		playerStateTextView.setText(text);
//		updateButtonVisibilities();
	}
	@Override
	public void onError(Exception e)
	{
		if (e instanceof UnsupportedDrmException)
		{
			// Special case DRM failures.
			UnsupportedDrmException unsupportedDrmException = (UnsupportedDrmException) e;
			int stringId = unsupportedDrmException.reason == UnsupportedDrmException.REASON_NO_DRM ? R.string.drm_error_not_supported
					: unsupportedDrmException.reason == UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME ? R.string.drm_error_unsupported_scheme
							: R.string.drm_error_unknown;
			Toast.makeText(getApplicationContext(), stringId, Toast.LENGTH_LONG)
					.show();
		}
		playerNeedsPrepare = true;
//		updateButtonVisibilities();
//		showControls();
	}
	@Override
	public void onVideoSizeChanged(int width, int height,
			float pixelWidthAspectRatio)
	{
		//shutterView.setVisibility(View.GONE);
		surfaceView.setVideoWidthHeightRatio(height == 0 ? 1
				: (width * pixelWidthAspectRatio) / height);
	}
	private RendererBuilder getRendererBuilder()
	{
		String userAgent = DemoUtil.getUserAgent(this);
		switch (contentType)
		{
		case DemoUtil.TYPE_HLS:
			Log.d(TAG, "Creating HlsRendererBuilder, uri="+contentUri.toString()+", userAgent="+userAgent+", contentid="+contentId);
			return new HlsRendererBuilder(
					userAgent, 
					contentUri.toString(),
					contentId);
		default:
			Log.d(TAG, "Creating DefaultRendererBuilder");
			return new DefaultRendererBuilder(
					this, 
					contentUri, 
					null);
		}
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
					}
					else
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
						startChatAudio();
						startBackgroundMovie();
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
			mVideoOutput.open(mVideoFormatInfo, true/*
													 * mSwDecodeCheckBox.isChecked
													 * ()
													 */);
			mVideoOutput.start();
		}

		mVideoInput.setShowView(mPreviewSfc);
		mVideoInput.open(mVideoFormatInfo, USE_SW_ENCODER);
		mVideoInput.start();

		mIsStarted = true;
	}
	private void startChatAudio()
	{
		mAudioLoop.setRecording(true);
		Log.d(TAG, "local audio loop started");
		mAudioLoop.start();
	}
	private void startBackgroundMovie()
	{
		if (player == null)
		{
			preparePlayer();
		}
		else if (player != null)
		{
			player.setBackgrounded(false);
		}
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
					String showStats = SystemProperties.get(
							"media.demo.showstats", null);
					if (showStats == null || showStats.equals(""))
						return;
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
			}
			else
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
			}
			else
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

	public static class Sample
	{

		public final String name;
		public final String contentId;
		public final String uri;
		public final int type;

		public Sample(String name, String uri, int type)
		{
			this(name, name.toLowerCase(Locale.US).replaceAll("\\s", ""), uri,
					type);
		}

		public Sample(String name, String contentId, String uri, int type)
		{
			this.name = name;
			this.contentId = contentId;
			this.uri = uri;
			this.type = type;
		}

	}
}
