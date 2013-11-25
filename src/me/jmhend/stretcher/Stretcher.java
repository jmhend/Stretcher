package me.jmhend.stretcher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.os.Handler;
import android.os.Message;
import android.view.MotionEvent;

/**
 * Class that handles TouchEvents and calculates when a View 
 * has a stretching gesture been applied to it.
 * 
 * @author jmhend
 */
public class Stretcher {

	private static final String TAG = Stretcher.class.getSimpleName();
	
////================================================================================
//// Static constants.
////================================================================================
	
	private static final double STRETCH_FACTOR = .88; 			// Exponential stretch factor to calculate stretch distance.
	private static final int UNSTRETCH_SPEED = 200; 			// pixels
	private static final int UNSTRETCH_REDRAW_TIME_UNIT = 14;	// millis
	private static final int THREAD_TIMEOUT = 800; 				// 800 milli seconds
	
////================================================================================
//// OnStretchListener
////================================================================================
	
	/**
	 * Listens for callbacks when stretch actions are recognized.
	 * @author jmhend
	 */
	public static interface OnStretchListener {
		
		/**
		 * Called on every calculation of a stretch distance.
		 * @param startY The initial y-position of the stretching gesture.
		 * @param distStretchedY How far the stretch has reached from startY.
		 */
		public void onStretch(float startY, float distStretchedY);
		
		/**
		 * Called when the stretching gesture starts.
		 */
		public void onStretchStart();
		
		/**
		 * Called when the stretching gesture ends.
		 * @param unstretched True if the Stretcher was unstretched to end the stretching, 
		 * false otherwise.
		 */
		public void onStretchEnd(boolean unstretched);
	}
	
////================================================================================
//// Member variables.
////================================================================================

	private ExecutorService mExecutorService;
	private Handler mHandler;	
	private boolean mHandlerReady = true;
	private List<OnStretchListener> mListeners = new ArrayList<OnStretchListener>();
	
	private boolean mIsStretching;
	private boolean mIsRunningUnstretch;
	private float mStartY;			// Initial y-position of contact.
	private float mTouchDeltaY;		// How far the most recent MotionEvent.MOVE is from the start y-position.
	private float mStretchY;		// How far the stretching factor has been calculated.
	
////================================================================================
//// Constructor.
////================================================================================
	
	/**
	 * Default
	 *  constructor.
	 */
	public Stretcher() {
		mExecutorService = Executors.newSingleThreadExecutor();
		mHandler = getUnstretchHandler();
		mHandlerReady = true;
	}
	
////================================================================================
//// Getters/Setters
////================================================================================
	
	/**
	 * Registers an OnStretchListener to respond to stretch actions.
	 * @param listener
	 */
	public void registerListener(OnStretchListener listener) {
		mListeners.add(listener);
	}
	
	/**
	 * Unregisters the OnStretchListener.
	 * @param listener
	 */
	public void unregisterListener(OnStretchListener listener) {
		mListeners.remove(listener);
	}
	
////================================================================================
//// Stretching.
////================================================================================
	
	/**
	 * Observe MotionEvents to determine stretching behavior.
	 * @param event
	 * @return True to consume the MotionEvent, false otherwise.
	 */
	public boolean onTouchEvent(MotionEvent event) {
		switch (event.getActionMasked()) {
		case MotionEvent.ACTION_DOWN:
			break;
		case MotionEvent.ACTION_MOVE:
			if (!mIsStretching) {
				break;
			}
			final float y = event.getY();
			if (y < mStartY) {
				stopStretching(false);
			} else {
				mTouchDeltaY = y - mStartY;
				handleStretch(mTouchDeltaY);
			}
			break;
		case MotionEvent.ACTION_UP:
		case MotionEvent.ACTION_CANCEL:
			if (mIsStretching) {
				stopStretching(true);
			}
			break;
		}
		return false;
	}
	
	/**
	 * Handles stretching calculation derived from the amount the MotionEvent have moved.
	 * @param touchDeltaY The y-position delta from the original startY.
	 */
	private void handleStretch(float touchDeltaY) {
		float stretchAmount = this.calculateStretch(touchDeltaY);
		mStretchY = stretchAmount;
		
		for (OnStretchListener l : mListeners) {
			l.onStretch(mStartY, mStretchY);
		}
	}
	
	/**
	 * Tells the Stretcher to begin observing stretching.
	 * @param y
	 */
	public void startStretching(float y) {
		mIsStretching = true;
		mStartY = y;
		
		for (OnStretchListener l : mListeners) {
			l.onStretchStart();
		}
	}
	
	/**
	 * Tells the Stretcher to stop observing stretching.
	 * @param unstretch True if the Stretcher should animate unstretch back to its initial state.
	 */
	public void stopStretching(boolean unstretch) {
		// Run the unstretch.
		if (unstretch) {
			runUnstretch();
			return;
		}
		
		// Just stop observing stretches.
		for (OnStretchListener l : mListeners) {
			l.onStretchEnd(unstretch);
		}
		reset();
	}
	
	/**
	 * @return True if the Stretcher is currently observing stretch gestures.
	 */
	public boolean isStretching() {
		return mIsStretching;
	}
	
	/**
	 * Automates the unstretching of this Stretcher from its current mStretchY to 0;
	 */
	private void runUnstretch() {
		mExecutorService.execute(getUnstretchRunnable());
	}
	
	/**
	 * Resets the Stretcher to an unstretched state.
	 */
	private void reset() {
		mIsStretching = false;
		mStretchY = 0f;
		mTouchDeltaY = 0f;
	}
	
	/**
	 * Calculates how far to stretch based upon the delta y-position moved.
	 * @param touchDeltaY
	 * @return
	 */
	private float calculateStretch(float touchDeltaY) {
		return (float) Math.pow(touchDeltaY, STRETCH_FACTOR);
	}
	
////=========================================================================================
////Runnable/Handler
////=========================================================================================
	
	/**
	 * @return Handler for updating the ui when the unstretch animation is running.
	 */
	private Handler getUnstretchHandler() {
		return new Handler() {
			/*
			 * (non-Javadoc)
			 * @see android.os.Handler#handleMessage(android.os.Message)
			 */
			@Override
			public void handleMessage(Message msg) {	
				// Make sure increments aren't too large/small.
				int lastDrawTime = msg.what;
				if (lastDrawTime > 200 || lastDrawTime <= 0) {
					lastDrawTime = 10;
				}

				// Unstretch.
				float deltaStretchDistance = (float) lastDrawTime / (float) UNSTRETCH_REDRAW_TIME_UNIT;
				mTouchDeltaY -= (int) (UNSTRETCH_SPEED * deltaStretchDistance);
				handleStretch(Math.max(0,mTouchDeltaY));

				// Unstretching has completed.
				if (mTouchDeltaY <= 0) {
					mIsRunningUnstretch = false;
					for (OnStretchListener l : mListeners) {
						l.onStretchEnd(true);
					}
					reset();
				}
				mHandlerReady = true;
			}
		};
	}
	
	/**
	 * @return Runnable for executing the unstretch animation
	 */
	private Runnable getUnstretchRunnable() {
		return new Runnable() {
			/*
			 * (non-Javadoc)
			 * @see java.lang.Runnable#run()
			 */
			@Override
			public void run() {
				mHandlerReady = true;
				mIsRunningUnstretch = true;
				long startTime = System.currentTimeMillis();
				long lastTime = 0;
				long currentTime = System.currentTimeMillis();
				
				while (mIsRunningUnstretch && (currentTime - startTime < THREAD_TIMEOUT)) {		
					currentTime = System.currentTimeMillis();
					if (mHandlerReady) {
						mHandlerReady = false;
						mHandler.sendEmptyMessage((int) (currentTime - lastTime));
						lastTime = currentTime;
					}
				}	
				mIsRunningUnstretch = false;
			}			
		};
	}
}
