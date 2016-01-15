package com.infocus.avpipe;

import java.io.IOException;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.SurfaceView;

public class MediaPlayerWrapper{
	private MediaPlayer mMediaPlayer = null;
	// private SurfaceView mSurfaceView = null ;
	private SurfaceView mSurfaceView = null;
	private final String TAG = "VideoPlayerThread";
	private String mSource;

	public MediaPlayerWrapper(SurfaceView surfaceView, String source) {
		this.mSurfaceView = surfaceView;
		this.mSource = source;
	}

	public void start() 
	{
		mMediaPlayer = new MediaPlayer();
		mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {  
		    @Override  
		    public void onCompletion(MediaPlayer mp) {
		    	mMediaPlayer.stop();
		    	mMediaPlayer.reset();
		    	try
				{
					mMediaPlayer.setDataSource(mSource);
				} catch (IllegalArgumentException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (SecurityException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IllegalStateException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IOException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		    	try
				{
					mMediaPlayer.prepare();
				} catch (IllegalStateException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IOException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		    	mMediaPlayer.start();
		    }  
		});
		// int idx = Integer.parseInt(System.getProperty("media.demo.hls.index",
		// "0"));
		// Log.d(TAG, "idx="+idx+", source="+sourceList[idx]);
		try 
		{
			mMediaPlayer.setDataSource(mSource);
		} 
		catch (IllegalArgumentException e) 
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 
		catch (SecurityException e) 
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 
		catch (IllegalStateException e) 
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 
		catch (IOException e) 
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		mMediaPlayer.setDisplay(mSurfaceView.getHolder());
		try 
		{
			mMediaPlayer.prepare();
		} 
		catch (IllegalStateException e) 
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 
		catch (IOException e) 
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		// mMediaPlayer.setOnBufferingUpdateListener(this);
		// mMediaPlayer.setOnCompletionListener(this);
		// mMediaPlayer.setOnPreparedListener(this);
		// mMediaPlayer.setOnVideoSizeChangedListener(this);
		mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
		mMediaPlayer.start();
		Log.d(TAG, "Player started");
	}
	public void stop()
	{
		mMediaPlayer.stop();
		mMediaPlayer.reset();
		mMediaPlayer.release();
		Log.d(TAG, "Player stopped");
	}
}
