package com.vfdev.getworkdonemusicapp;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import timber.log.Timber;

public class MusicService extends Service
        implements MediaPlayer.OnCompletionListener,
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener,
        AudioManager.OnAudioFocusChangeListener {

    private final static String TAG = MusicService.class.getName();

//    private NotificationManager mNotificationManager;
    private static final int NOTIFICATION_ID = 1;
    private Bitmap mServiceIcon;

    // Wifi manager
    private WifiManager.WifiLock mWifiLock;

    // AudioManager
    private AudioManager mAudioManager;

    // Bound service
    private IBinder mBinder;
    private IMusicServiceCallbacks mCallbacks;

    // MediaPlayer
    private MediaPlayer mMediaPlayer;

    // Service states
    private enum State {
        Retrieving, // retrieve track ids from SoundCloud
        Stopped,    // media player is stopped and not prepared to play
        Preparing,  // media player is preparing...
        Playing,    // playback active (media player ready!)
        Paused      // playback paused (media player ready!)
    }
    private boolean hasAudiofocus=false;
    private State mState = State.Stopped;
    private Bundle mFullState;


    // Connection
    private OkHttpClient mSoundCloudClient;
    private String CLIENT_ID;

    private final static int HTTP_OK=200;
    private final static String API_URL="http://api.soundcloud.com/";
    private final static String REQUEST_TRACKS_URL=API_URL+"tracks.json?genres=";
    private final static String REQUEST_A_TRACK_URL=API_URL+"tracks/";
    private final static String TRACKS_LIMIT="100";

    // Tracks
    private ArrayList<Integer> mTracksHistory;
    private ArrayList<String> mTracks;
    private ArrayList<String> mStyles;

    // AsyncTasks
    private DownloadTrackInfo mDownloadTrackInfo;
    private DownloadTrackIds mDownloadTrackIds;


    // -------- Service methods

    @Override
    public void onCreate() {
//        Log.i(TAG, "Creating service");
        Timber.v(TAG + " : " + "Creating service");

//        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mServiceIcon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher);

        mBinder = new LocalBinder();

        mFullState = new Bundle();

        CLIENT_ID=getString(R.string.soundCloud_client_id);
        mSoundCloudClient = new OkHttpClient();

        mMediaPlayer = new MediaPlayer();

        // Make sure the media player will acquire a wake-lock while playing. If we don't do
        // that, the CPU might go to sleep while the song is playing, causing playback to stop.
        mMediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);

        mMediaPlayer.setOnErrorListener(this);
        mMediaPlayer.setOnPreparedListener(this);
        mMediaPlayer.setOnCompletionListener(this);
        mMediaPlayer.reset();

        // fetch tracks
        mTracks = new ArrayList<String>();
        mTracksHistory = new ArrayList<Integer>();

        mStyles = new ArrayList<String>();
        mStyles.add("trance");
        mStyles.add("electro");

        // Create the Wifi lock (this does not acquire the lock, this just creates it)
        mWifiLock = ((WifiManager) getSystemService(Context.WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL, "MY_WIFI_LOCK");
        mWifiLock.acquire();

        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        showNotification(getString(R.string.no_tracks));
    }

    /**
     * Called when we receive an Intent. When we receive an intent sent to us via startService(),
     * this is the method that gets called. So here we react appropriately depending on the
     * Intent's action, which specifies what is being requested of us.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
//        Log.i(TAG, "onStartCommand");
        Timber.v(TAG + " : " + "onStartCommand");

        retrieveTrackIds();
        return START_NOT_STICKY; // Don't automatically restart this Service if it is killed
    }


    @Override
    public void onDestroy() {
        // Service is being killed, so make sure we release our resources
//        Log.i(TAG, "Destroy music service");
        Timber.v(TAG + " : " + "Destroy music service");

        mState = State.Stopped;
        releaseResources();
    }

    @Override
    public IBinder onBind(Intent arg0) {
//        Log.i(TAG, "onBind");
        Timber.v(TAG + " : " + "onBind");

        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
//        Log.i(TAG, "onUnbind");
        Timber.v(TAG + " : " + "onUnbind");

        mCallbacks = null;
        return true;
    }


    // -------- MediaPlayer listeners

    public void onCompletion(MediaPlayer player) {
//        Log.i(TAG, "onCompletion MediaPlayer");
        Timber.v(TAG + " : " + "onCompletion MediaPlayer");

        // The media player finished playing the current Track, so we go ahead and start the next.
        playNextTrack();
    }

    public void onPrepared(MediaPlayer player) {
//        Log.i(TAG, "onPrepared MediaPlayer");
        Timber.v(TAG + " : " + "onPrepared MediaPlayer");

        // The media player is done preparing. That means we can start playing!
        mState = State.Playing;
        showNotification(mFullState.getString("TrackTitle"));
        mFullState.putInt("TrackDuration", player.getDuration());

        if (mTracksHistory.size() > 1) {
            mFullState.putBoolean("HasPrevTrack", true);
        } else {
            mFullState.putBoolean("HasPrevTrack", false);
        }

        if (mCallbacks != null) {
            mCallbacks.onMediaPlayerPrepared(mFullState);
        }

        // This handles the case when :
        // audio focus is lost while service was in State.Preparing
        // Player should not start
        if (hasAudiofocus) {
//            Log.i(TAG, "Has audio focus. Start playing");
            Timber.v(TAG + " : " + "Has audio focus. Start playing");

            if (mCallbacks != null) {
                mCallbacks.onMediaPlayerStarted();
            }
            player.start();
        } else {
//            Log.i(TAG, "Has no audio focus. Do not start");
            Timber.v(TAG + " : " + "Has no audio focus. Do not start");

        }
    }

    public boolean onError(MediaPlayer mp, int what, int extra) {
//        Log.e(TAG, "Error: what=" + String.valueOf(what) + ", extra=" + String.valueOf(extra));
        Timber.i(TAG + " : " + "what=" + String.valueOf(what) + ", extra=" + String.valueOf(extra));

        mState = State.Stopped;
        return true; // true indicates we handled the error
    }


    // ------------ Local binder
    public class LocalBinder extends Binder {
        MusicService getService() {
            return MusicService.this;
        }
    }

    // ------------ OnAudioFocusChangeListener
    @Override
    public void onAudioFocusChange(int focusChange) {
//        Log.i(TAG, "onAudioFocusChange");
        Timber.v(TAG + " : " + "onAudioFocusChange");

        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
//            Log.i(TAG, "AUDIOFOCUS_LOSS_TRANSIENT");
            Timber.v(TAG + " : " + "AUDIOFOCUS_LOSS_TRANSIENT");

            hasAudiofocus=false;
            // Pause playback
            if (mMediaPlayer.isPlaying()) {
                pause();
                mState = State.Playing;
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
//            Log.i(TAG, "AUDIOFOCUS_GAIN");
            Timber.v(TAG + " : " + "AUDIOFOCUS_GAIN");

            // Resume playback
            hasAudiofocus=true;
            if (!mMediaPlayer.isPlaying() &&
                    mState == State.Playing) {
                mState = State.Paused;
                play();
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
//            Log.i(TAG, "AUDIOFOCUS_LOSS");
            Timber.v(TAG + " : " + "AUDIOFOCUS_LOSS");

            // Audio focus loss is permanent
            releaseAudioFocus();
        }
    }

    // ---------- Music service callback Interface
    public interface IMusicServiceCallbacks {
        public void onDownloadTrackIdsPostExecute(Bundle result);
        public void onDownloadTrackIdsStarted();
        public void onDownloadTrackInfoPostExecute(Bundle result);

        public void onMediaPlayerPrepared(Bundle result);
        public void onMediaPlayerStarted();
        public void onMediaPlayerPaused();
        public void onMediaPlayerIsPreparing();

        public void onShowErrorMessage(String msg);
    }

    // ---------- DownloadTrackIds AsyncTask
    private class DownloadTrackIds extends AsyncTask<Void, Void, Bundle> {
        protected Bundle doInBackground(Void... params) {

            Bundle result = new Bundle();
            for (String style : mStyles) {
//                Log.i(TAG, "DownloadTrackIds : request track of genre : " + style);
                Timber.v(TAG + " : " + "DownloadTrackIds : request track of genre : " + style);

                String requestUrl = REQUEST_TRACKS_URL;
                requestUrl += style;
                requestUrl += "&limit=" + TRACKS_LIMIT;
                requestUrl += "&offset=" + String.valueOf(new Random().nextInt(1000));
                requestUrl += "&client_id=" + CLIENT_ID;

                try {
                    Request request = new Request.Builder()
                            .url(requestUrl).build();

                    Response response = mSoundCloudClient.newCall(request).execute();
                    int code = response.code();
                    String responseStr = response.body().string();
                    if (code == HTTP_OK) {
                        // Parse the response:
                        JSONArray tracksJSON = new JSONArray(responseStr);
//                        Log.i(TAG, "getTracksInBackground : found " + tracksJSON.length() + " tracks");
                        Timber.v(TAG + " : " + "getTracksInBackground : found " + tracksJSON.length() + " tracks");

                        for (int i = 0; i < tracksJSON.length(); i++) {
                            JSONObject trackJSON = tracksJSON.getJSONObject(i);
                            if (trackJSON.getBoolean("streamable")) {
                                String id = trackJSON.getString("id");
                                mTracks.add(id);
                            }
                        }
                    } else {
//                        Log.e(TAG, "getTracksInBackground : request error : " + responseStr);
                        Timber.e(TAG + " : " + "getTracksInBackground : request error : " + responseStr);

                        result.putBoolean("Result", false);
                        result.putString("Message", getString(R.string.app_err));
                        return result;
                    }
                } catch (IOException e) {
//                    e.printStackTrace();
//                    Log.e(TAG, "getTracksInBackground : SoundCloud get request error : " + e.getMessage());
                    Timber.i(e, TAG + " : " + "getTracksInBackground : SoundCloud get request error : " + e.getMessage());
                    result.putBoolean("Result", false);
                    result.putString("Message", getString(R.string.connx_err));
                    return result;
                } catch (JSONException e) {
//                    e.printStackTrace();
//                    Log.e(TAG, "getTracksInBackground : JSON parse error : " + e.getMessage());
                    Timber.e(e, TAG + " : " + "getTracksInBackground : JSON parse error : " + e.getMessage());
                    result.putBoolean("Result", false);
                    result.putString("Message", getString(R.string.app_err));
                    return result;
                }
            }
            result.putBoolean("Result", true);
            result.putString("Message", "");
            return result;

        }

        protected void onPostExecute(Bundle result) {

            if (mCallbacks != null) {
                mCallbacks.onDownloadTrackIdsPostExecute(result);
            }
            mState = State.Stopped;
            mDownloadTrackIds=null;
        }
    }

    // --------- DownloadTrackInfo AsyncTask
    private class DownloadTrackInfo extends AsyncTask<String, Void, Bundle> {
        protected Bundle doInBackground(String... requestUrl) {

            Bundle result = new Bundle();
            try {
                Request request = new Request.Builder()
                        .url(requestUrl[0])
                        .build();
                Response response = mSoundCloudClient.newCall(request).execute();
                int code = response.code();
                String responseStr = response.body().string();
                if (code == HTTP_OK) {
                    result.putBoolean("Result", true);
                    result.putString("JSONResult", responseStr);
                } else {
//                    Log.e(TAG, "DownloadTrackInfo : request error : " + responseStr);
                    Timber.e(TAG + " : " + "DownloadTrackInfo : request error : " + responseStr);
                    result.putBoolean("Result", false);
                    result.putString("Message", getString(R.string.app_err));
                }
            } catch (IOException e) {
//                e.printStackTrace();
//                Log.e(TAG, "DownloadTrackInfo : request error : " + e.getMessage());
                Timber.i(e, TAG + " : " + "DownloadTrackInfo : request error : " + e.getMessage());
                result.putBoolean("Result", false);
                result.putString("Message", getString(R.string.connx_err));
            }
            return result;
        }
        protected void onPostExecute(Bundle result) {

            if (!result.getBoolean("Result")) {
                if (mCallbacks!=null) {
                    mCallbacks.onShowErrorMessage(result.getString("Message"));
                }
                mState = State.Stopped;
                mDownloadTrackInfo=null;
                return;
            }

            try {
                JSONObject trackJSON = new JSONObject(result.getString("JSONResult"));
                // prepare player:
                if (trackJSON.getBoolean("streamable")) {
                    String stream_url = trackJSON.getString("stream_url");
//                    mMediaPlayer.reset();
                    mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    mMediaPlayer.setDataSource(stream_url + "?client_id=" + CLIENT_ID);
                    mMediaPlayer.prepareAsync();
                }

                // get title:
                mFullState.putString("TrackTitle", trackJSON.getString("title"));
                // get waveform:
                mFullState.putString("TrackWaveformUrl", trackJSON.getString("waveform_url"));

                if (mCallbacks != null) {
                    mCallbacks.onDownloadTrackInfoPostExecute(mFullState);
                }

            } catch (IOException e) {
//                e.printStackTrace();
//                Log.e(TAG, "DownloadTrackInfo : onPostExecute : request error : " + e.getMessage());
                Timber.e(e, TAG + " : " + "DownloadTrackInfo : onPostExecute : request error : " + e.getMessage());
                if (mCallbacks != null) {
                    mCallbacks.onShowErrorMessage(getString(R.string.app_err));
                }
                mState = State.Stopped;

            } catch (JSONException e) {
//                e.printStackTrace();
//                Log.e(TAG, "DownloadTrackInfo : onPostExecute : JSON parse error : " + e.getMessage());
                Timber.e(e, TAG + " : " + "DownloadTrackInfo : onPostExecute : JSON parse error : " + e.getMessage());

                if (mCallbacks != null) {
                    mCallbacks.onShowErrorMessage(getString(R.string.app_err));
                }
                mState = State.Stopped;
            }
            mDownloadTrackInfo=null;
        }
    }


    // ------------ Other methods

    private void showNotification(String trackTitle) {

//        Log.i(TAG,"Show notification");
        Timber.v(TAG + " : " + "Show notification");

        // Create a notification area notification so the user
        // can get back to the MainActivity
        final Intent notificationIntent = new Intent(getApplicationContext(), MainActivity.class);
        final PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        final Notification notification = new Notification.Builder(getApplicationContext())
                .setContentTitle(getString(R.string.app_name))
                .setContentText(trackTitle)
                .setSmallIcon(R.drawable.ic_launcher)
                .setLargeIcon(mServiceIcon)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        // Put this Service in a foreground state, so it won't
        // readily be killed by the system
        startForeground(NOTIFICATION_ID, notification);

    }

    public void setMusicServiceCallbacks(IMusicServiceCallbacks callbacks) {
        mCallbacks = callbacks;
    }

    public Bundle getCurrentState() {
        if (mState == State.Paused ||
                mState == State.Playing) {
            mFullState.putBoolean("IsPlaying", isPlaying());
        }
        return mFullState;
    }

    public boolean isPlaying() {
        return mState == State.Playing;
    }

    public void pause(){

        mMediaPlayer.pause();
        mState = State.Paused;
        if (mCallbacks != null) {
            mCallbacks.onMediaPlayerPaused();
        }

    }

    public boolean play() {

        if (mTracks.size() == 0) {
            retrieveTrackIds();
            return false;
        }

        if (mState == State.Paused) {
            mMediaPlayer.start();
            mState = State.Playing;
            if (mCallbacks != null) {
                mCallbacks.onMediaPlayerStarted();
            }
            return true;
        } else if (mState == State.Stopped) {
            // Only when Service is stopped, request audio focus
            if (requestAudioFocus()) {
                playNextTrack();
                return true;
            }
        }

        return false;
    }

    public int getTrackDuration() {
        return mMediaPlayer.getDuration();
    }

    public int getTrackCurrentPosition() {
        return mMediaPlayer.getCurrentPosition();
    }

    public void playNextTrack() {

        // Prepare player : get track's info: title, duration, stream_url,  waveform_url
//        Log.i(TAG, "playNextTrack : DownloadTrackInfo");
        Timber.v(TAG + " : " + "playNextTrack : DownloadTrackInfo");


        int index = new Random().nextInt(mTracks.size());
        prepareAndPlay(index);
        mTracksHistory.add(index);

        if (mTracksHistory.size() > Integer.parseInt(TRACKS_LIMIT)) {
            mTracksHistory.remove(0);
        }

    }

    public void playPrevTrack() {
        if (mTracksHistory.size() > 1) {
            mTracksHistory.remove(mTracksHistory.size() - 1);
            int index = mTracksHistory.get(mTracksHistory.size() - 1);
            prepareAndPlay(index);
        }
    }


    public void rewindTrackTo(int seconds) {
        mMediaPlayer.seekTo(seconds);
    }

    // prepare and play the track at index
    void prepareAndPlay(int trackIndex) {

        if (mDownloadTrackInfo == null) {

            if (mCallbacks != null) {
                mCallbacks.onMediaPlayerIsPreparing();
            }
            mMediaPlayer.reset();
            mState = State.Preparing;

            // Prepare player : get track's info: title, duration, stream_url,  waveform_url
            String requestUrl = REQUEST_A_TRACK_URL;
            requestUrl += mTracks.get(trackIndex) + ".json";
            requestUrl += "?client_id=" + CLIENT_ID;

            mDownloadTrackInfo = new DownloadTrackInfo();
            mDownloadTrackInfo.execute(requestUrl);
        }
    }


    private void retrieveTrackIds() {

        if (mState != State.Retrieving &&
                mTracks.size() == 0 ) {
//            Log.i(TAG, "Retrieve track ids");
            Timber.v(TAG + " : " + "Retrieve track ids");

            if (mDownloadTrackIds == null) {
                mState = State.Retrieving;

                if (mCallbacks != null) {
                    mCallbacks.onDownloadTrackIdsStarted();
                }

                mDownloadTrackIds = new DownloadTrackIds();
                mDownloadTrackIds.execute();
            }

        }

    }


    boolean requestAudioFocus() {
        // request audio focus :
        int result = mAudioManager.requestAudioFocus(this,
                // Use the music stream.
                AudioManager.STREAM_MUSIC,
                // Request permanent focus.
                AudioManager.AUDIOFOCUS_GAIN);

        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
//            Log.i(TAG, "Audio focus request is not granted");
            Timber.v(TAG + " : " + "Audio focus request is not granted");

            if (mCallbacks != null) {
                mCallbacks.onShowErrorMessage(getString(R.string.no_audio_focus_err));
            }
            hasAudiofocus=false;
        } else {
            hasAudiofocus = true;
        }
        return hasAudiofocus;
    }

    void releaseAudioFocus() {
        hasAudiofocus=false;
        mAudioManager.abandonAudioFocus(this);
    }


    void releaseResources() {

        // stop being a foreground service
        stopForeground(true);

        // stop and release the Media Player, if it's available
        if (mMediaPlayer != null) {
            mMediaPlayer.reset();
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
        // we can also release the Wifi lock, if we're holding it
        if (mWifiLock.isHeld()) mWifiLock.release();

        // release audio focus
        releaseAudioFocus();


    }





}