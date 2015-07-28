package com.vfdev.gettingthingsdonemusicapp;

import android.app.Activity;
import android.content.Intent;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.support.v13.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.net.Uri;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import butterknife.ButterKnife;
import timber.log.Timber;

import com.j256.ormlite.android.apptools.OpenHelperManager;
import com.j256.ormlite.dao.RuntimeExceptionDao;
import com.vfdev.gettingthingsdonemusicapp.DB.DBTrackInfo;
import com.vfdev.gettingthingsdonemusicapp.DB.DatabaseHelper;
import com.vfdev.gettingthingsdonemusicapp.Fragments.FavoriteTracksFragment;
import com.vfdev.gettingthingsdonemusicapp.Fragments.MainFragment;
import com.vfdev.gettingthingsdonemusicapp.Fragments.PlaylistFragment;
import com.vfdev.mimusicservicelib.MusicService;
import com.vfdev.mimusicservicelib.MusicServiceHelper;
import com.vfdev.mimusicservicelib.core.MusicPlayer;
import com.vfdev.mimusicservicelib.core.SoundCloundProvider;
import com.vfdev.mimusicservicelib.core.TrackInfo;

import de.greenrobot.event.EventBus;

/* TODO:
 1) Normal usage

    ---- version 1.0

    1.1) Fetch track ids on genres = OK
    1.2) Play/Pause a track = OK
        1.2.1) when track is finished, play next track = OK
    1.3) Play next/previous track = OK
    1.4) Play on background -> Use a service = OK
    1.5) Save track waveform in the service and do not reload from URL on activity UI restore = OK
    1.6) Visual response on press next/prev track buttons = OK
    1.7) Click on title -> open track in the browser = OK

    ---- version 1.1
    1.8) Random choice of track and do not repeat : check id in track history
    1.9) Settings : configure retrieved styles by keywords (default: trance, electro)
        - replace search genres by tags:
        default tags : trance,electronic,armin,Dash Berlin,ASOT
        - add option to search as query instead of tags

    ---- version 2.0
    2.0) View Pager : view1 = Main, view2 = List of played tracks, view3 = Favorite tracks
    2.1) OK = Add local DB to store conf:
    2.2) Download playing track
    2.3) PlaylistFragment : tracklist : item = { track name/tags ; duration ; star }
        a) Item onClick : play track -> remove all track after
    2.4) Track Title onClick : AlertDialog : {Mark as favorite; Download to phone; Open in SoundCloud}

    2.x) Show prev track button when starts to prepare next track -> can go to prev track if no network


 2) Abnormal usage
    2.1) No network

 3) BUGS :

    1) play track -> next -> back -> restore from notification

 */

public class MainActivity extends Activity implements
        SettingsDialog.SettingsDialogCallback,
        MainFragment.OnTrackClickListener,
        FavoriteTracksFragment.OnPlayFavoriteTracksListener
{

    // UI
    private ProgressDialog mProgressDialog;
    // Pager View/Adapter
    private ViewPager mViewPager;
    private PagerAdapter mPagerAdapter;
    // Fragments
    private MainFragment mMainFragment;
    private PlaylistFragment mPlaylistFragment;
    private FavoriteTracksFragment mFavoriteTracksFragment;

    // MusicService helper
    private MusicServiceHelper mMSHelper;

    // Toast dialog
    private Toast mToast;

    private DatabaseHelper mDBHandler;
    private RuntimeExceptionDao<DBTrackInfo, String> mREDao;

    // ------- Activity methods

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Timber.v("onCreate");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.inject(this);

        // set custom title :
        setTitle(R.string.main_activity_name);

        // setup MusicServiceHelper
        mMSHelper = new MusicServiceHelper(this, new SoundCloundProvider(), MainActivity.class);

        // DB
        setupDB();

        // initialize some fragments (uses mMSHelper)
        setupPagerUI();

        mProgressDialog = new ProgressDialog(this);
        mToast = Toast.makeText(getApplicationContext(), "", Toast.LENGTH_LONG);

        EventBus.getDefault().register(this);

        mMSHelper.startMusicService();

    }

    @Override
    protected void onStart() {
        super.onStart();
        Timber.v("onStart");
    }

    @Override
    protected void onStop() {
        Timber.v("onStop");
        super.onStop();
    }

    @Override
    protected void onPause(){
        Timber.v("onPause");
        super.onPause();

    }

    @Override
    protected void onResume(){
        Timber.v("onResume");
        super.onResume();

    }

    @Override
    protected void onDestroy() {
        Timber.v("onDestroy");

        mMSHelper.release();
        EventBus.getDefault().unregister(this);

        // Close database connection
        if (mDBHandler != null) {
            OpenHelperManager.releaseHelper();
            mDBHandler = null;
        }

        super.onDestroy();
    }

    // ---------- Menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_about) {
            about();
        } else if (id == R.id.action_exit) {
            exit();
            return true;
        } else if (id == R.id.action_settings) {
            settings();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    // ----------- MusicServiceHelper.ReadyEvent

    public void onEvent(MusicServiceHelper.ReadyEvent event) {
        ArrayList<TrackInfo> playlist = mMSHelper.getPlaylist();
        if (playlist.isEmpty()) {
            // get tags from settings :
            String query = getTags();
            Timber.v("onReady -> setupTracks : " + query);
            mMSHelper.setupTracks(query);
        }
    }

    // --------- MusicPlayer.ErrorEvent & MusicService.ErrorEvent

    public void onEvent(MusicPlayer.ErrorEvent event) {
        Timber.v("onEvent : MusicPlayer.ErrorEvent : event.code=" + event.code);
        if (event.code == MusicPlayer.ERROR_DATASOURCE ||
                event.code == MusicPlayer.ERROR_APP ||
                event.code == MusicPlayer.ERROR_NO_AUDIOFOCUS) {
            Toast.makeText(this, R.string.app_err, Toast.LENGTH_SHORT).show();
        }
    }

    public void onEvent(MusicService.ErrorEvent event) {
        Timber.v("onEvent : MusicService.ErrorEvent : event.code=" + event.code);
        if (event.code == MusicService.APP_ERR) {
            Toast.makeText(this, "Ops, there is an application error", Toast.LENGTH_SHORT).show();
        } else if (event.code == MusicService.NOTRACKS_ERR) {
            Toast.makeText(this, "No tracks found", Toast.LENGTH_SHORT).show();
        } else if (event.code == MusicService.CONNECTION_ERR) {
            Toast.makeText(this, "Ops, internet connection problem", Toast.LENGTH_SHORT).show();
        } else if (event.code == MusicService.QUERY_ERR) {
            Toast.makeText(this, "There is a problem with your query", Toast.LENGTH_SHORT).show();
        }
    }

    // --------- MainFragment.OnTrackClickListener

    @Override
    public void onClick(TrackInfo info) {

        // Open Alert dialog :
        // 1) Mark/Unmark as 'Favorite' (!!!)
        // 2) Open in SoundCloud
        // 3) Download to phone (or already downloaded)

        final TrackInfo trackInfo = info;
        final boolean isFavorite = mREDao.idExists(trackInfo.id);
        final CharSequence [] trackChoices = new CharSequence[2];
        if (!isFavorite) {
            trackChoices[0] = getString(R.string.mark_as_favorite);
        } else {
            trackChoices[0] = getString(R.string.remove_from_favorite);
        }

        trackChoices[1] = getString(R.string.open_in_SC);
//        trackChoices[2] = getString(R.string.download);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.on_track_clicked_menu)
                .setItems(trackChoices, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        // The 'which' argument contains the index position
                        // of the selected item
                        if (which == 0) {
                            // Mark/Unmark as 'Favorite'
                            mPlaylistFragment.setFavorite(!isFavorite, trackInfo);
                        } else if (which == 2) {
                            // Download to phone

                        } else if (which == 1) {
                            // Open in SoundCloud
                            if (trackInfo.fullInfo.containsKey("permalink_url")) {
                                Uri uri = Uri.parse(trackInfo.fullInfo.get("permalink_url"));
                                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                                startActivity(i);
                            }
                        }
                    }
                });
        AlertDialog dialog = builder.create();
        dialog.show();

    }

    // --------- FavoriteTracksFragment.OnPlayFavoriteTracksListener

    @Override
    public void onPlay(List<DBTrackInfo> tracks) {
        Timber.v("onPlay");
        if (mMSHelper.getPlayer() != null) {
            mMSHelper.getPlayer().clearTracks();
            for (DBTrackInfo track : tracks) {
                mMSHelper.getPlayer().addTrack(track.trackInfo);
            }
        }
    }


    // --------- Other class methods

    private void showMessage(String msg) {
        mToast.setText(msg);
        mToast.show();
    }

    private void startProgressDialog(String msg) {
        mProgressDialog.setCanceledOnTouchOutside(false);
        mProgressDialog.setCancelable(false);
        mProgressDialog.setMessage(msg);
        mProgressDialog.show();
    }

    private void setupPagerUI() {
        Timber.v("setupPagerUI");

        // Create fragments:
        mMainFragment = new MainFragment();
        mMainFragment.setHelper(mMSHelper);

        mPlaylistFragment = new PlaylistFragment();
        mPlaylistFragment.setHelper(mMSHelper);

        mFavoriteTracksFragment = new FavoriteTracksFragment();

        // Create pager adapter
        mPagerAdapter = new PagerAdapter(getFragmentManager());
        mPagerAdapter.appendFragment(mMainFragment);
        mPagerAdapter.appendFragment(mPlaylistFragment);
        mPagerAdapter.appendFragment(mFavoriteTracksFragment);

        // Create and set up the ViewPager .
        mViewPager = (ViewPager) findViewById(R.id.pager);
        mViewPager.setAdapter(mPagerAdapter);

        mViewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                Timber.v("onPageSelected : " + String.valueOf(position));
            }
        });

    }

    private void setupDB() {
        Timber.v("Setup DB");
        mDBHandler = OpenHelperManager.getHelper(this, DatabaseHelper.class);
        mREDao = mDBHandler.getTrackInfoREDao();
    }

    // -------------- Get DatabaseHelper
    private DatabaseHelper getHelper() {
        if (mDBHandler == null) {
            mDBHandler = OpenHelperManager.getHelper(this, DatabaseHelper.class);
        }
        return mDBHandler;
    }

    private void exit() {
        Timber.v("exit");
        mMSHelper.stopMusicService();
        finish();
    }

    private void about() {

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.about_dialog_title)
                .setView(getLayoutInflater().inflate(R.layout.alertdialog_about, null));
        AlertDialog dialog = builder.create();
        dialog.show();

    }

    private void settings() {
        SettingsDialog dialog = new SettingsDialog(this);
        dialog.setData(getTags());
        dialog.show();

    }

    private String getTags() {
        SharedPreferences prefs = getSharedPreferences("Tags",0);
        return prefs.getString("Tags", getString(R.string.settings_default_tags));
    }

    private void writeTags(String tags) {
        Timber.v("writeTags : " + tags);
        // We need an Editor object to make preference changes.
        // All objects are from android.context.Context
        SharedPreferences prefs = getSharedPreferences("Tags", 0);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("Tags", tags);
        // Commit the edits!
        editor.commit();
    }

    private void setupNewTags(String tags) {

        if (tags.isEmpty()) return;

        writeTags(tags);
        Toast.makeText(this, getString(R.string.tags_updated), Toast.LENGTH_SHORT).show();

        // start retrieving tracks for new tags
        mMSHelper.clearPlaylist();
        mMSHelper.setupTracks(tags);
    }

    // ----------- SettingsDialog.SettingsDialogCallback

    @Override
    public void onUpdateData(String newTags) {
        setupNewTags(newTags);
    }

    @Override
    public void onResetDefault() {
        setupNewTags(getString(R.string.settings_default_tags));
    }

    // ------------ PagerAdapter

    private class PagerAdapter extends FragmentPagerAdapter {

        private ArrayList<Fragment> mFragments;

        public PagerAdapter(FragmentManager fm) {
            super(fm);
            // Setup fragments:
            mFragments = new ArrayList<Fragment>();
        }

        public void appendFragment(Fragment f) {
            mFragments.add(f);
        }


        @Override
        public Fragment getItem(int position) {

            if (position >=0 && position < mFragments.size()) {
                return mFragments.get(position);
            }
            return null;
        }

        @Override
        public int getCount() {
            return mFragments.size();
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return "Fragment Title";
        }

    }


}