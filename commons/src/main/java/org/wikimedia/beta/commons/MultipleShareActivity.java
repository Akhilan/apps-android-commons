package org.wikimedia.beta.commons;

import java.util.*;
import java.util.concurrent.*;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.support.v4.app.FragmentManager;
import android.text.*;
import android.view.*;
import android.widget.*;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;

import org.wikimedia.beta.commons.auth.*;
import org.wikimedia.beta.commons.contributions.*;
import org.wikimedia.beta.commons.media.*;

public  class       MultipleShareActivity
        extends     AuthenticatedActivity
        implements  MediaDetailPagerFragment.MediaDetailProvider,
                    AdapterView.OnItemClickListener,
                    FragmentManager.OnBackStackChangedListener {
    private CommonsApplication app;
    private ArrayList<Contribution> photosList = null;

    private MultipleUploadListFragment uploadsList;
    private MediaDetailPagerFragment mediaDetails;


    public MultipleShareActivity() {
        super(WikiAccountAuthenticator.COMMONS_ACCOUNT_TYPE);
    }

    public Media getMediaAtPosition(int i) {
        return photosList.get(i);
    }

    public int getTotalMediaCount() {
        if(photosList == null) {
            return 0;
        }
        return photosList.size();
    }

    public void notifyDatasetChanged() {
        if(uploadsList != null) {
            uploadsList.notifyDatasetChanged();
        }
    }


    public void onItemClick(AdapterView<?> adapterView, View view, int index, long item) {
        showDetail(index);

    }

    private class StartMultipleUploadTask extends AsyncTask<Void, Integer, Void> {

        ProgressDialog dialog;

        @Override
        protected Void doInBackground(Void... voids) {
            for(int i = 0; i < photosList.size(); i++) {
                Contribution up = photosList.get(i);
                String curMimetype = (String)up.getTag("mimeType");
                if(curMimetype == null || TextUtils.isEmpty(curMimetype) || curMimetype.endsWith("*")) {
                    String mimeType = getContentResolver().getType(up.getLocalUri());
                    if(mimeType != null) {
                        up.setTag("mimeType", mimeType);
                    }
                }

                StartUploadTask startUploadTask = new StartUploadTask(MultipleShareActivity.this, uploadService, up);
                try {
                    Utils.executeAsyncTask(startUploadTask);
                    startUploadTask.get();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } catch (ExecutionException e) {
                    throw new RuntimeException(e);
                }
                this.publishProgress(i);

            }
            return null;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            dialog = new ProgressDialog(MultipleShareActivity.this);
            dialog.setIndeterminate(false);
            dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            dialog.setMax(photosList.size());
            dialog.setTitle(getResources().getQuantityString(R.plurals.starting_multiple_uploads, photosList.size(), photosList.size()));
            dialog.show();
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            dialog.setProgress(values[0]);
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            dialog.dismiss();
            Toast startingToast = Toast.makeText(getApplicationContext(), R.string.uploading_started, Toast.LENGTH_LONG);
            startingToast.show();
            finish();
        }
    }

    private UploadService uploadService;
    private boolean isUploadServiceConnected;
    private ServiceConnection uploadServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName componentName, IBinder binder) {
            uploadService = (UploadService) ((HandlerService.HandlerServiceLocalBinder)binder).getService();
            isUploadServiceConnected = true;
        }

        public void onServiceDisconnected(ComponentName componentName) {
            // this should never happen
            throw new RuntimeException("UploadService died but the rest of the process did not!");
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getSupportMenuInflater().inflate(R.menu.activity_multiple_share, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_upload_multiple:

                StartMultipleUploadTask startUploads = new StartMultipleUploadTask();
                Utils.executeAsyncTask(startUploads);
                return true;
            case android.R.id.home:
                if(mediaDetails.isVisible()) {
                    getSupportFragmentManager().popBackStack();
                }
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_multiple_uploads);
        app = (CommonsApplication)this.getApplicationContext();

        if(savedInstanceState != null) {
            photosList = savedInstanceState.getParcelableArrayList("uploadsList");
        }

        getSupportFragmentManager().addOnBackStackChangedListener(this);
        requestAuthToken();

    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(isUploadServiceConnected) {
            unbindService(uploadServiceConnection);
        }
    }

    private void showDetail(int i) {
        if(mediaDetails == null ||!mediaDetails.isVisible()) {
            mediaDetails = new MediaDetailPagerFragment(true);
            this.getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.uploadsFragmentContainer, mediaDetails)
                    .addToBackStack(null)
                    .commit();
            this.getSupportFragmentManager().executePendingTransactions();
        }
        mediaDetails.showImage(i);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelableArrayList("uploadsList", photosList);
    }

    @Override
    protected void onAuthCookieAcquired(String authCookie) {
        app.getApi().setAuthCookie(authCookie);
        Intent intent = getIntent();

        if(intent.getAction().equals(Intent.ACTION_SEND_MULTIPLE)) {
            if(photosList == null) {
                photosList = new ArrayList<Contribution>();
                ArrayList<Uri> urisList = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                for(int i=0; i < urisList.size(); i++) {
                    Contribution up = new Contribution();
                    Uri uri = urisList.get(i);
                    up.setLocalUri(uri);
                    up.setTag("mimeType", intent.getType());
                    up.setTag("sequence", i);
                    up.setSource(Contribution.SOURCE_EXTERNAL);
                    up.setMultiple(true);
                    photosList.add(up);
                }
            }

            uploadsList = (MultipleUploadListFragment) getSupportFragmentManager().findFragmentByTag("uploadsList");
            if(uploadsList == null) {
                uploadsList =  new MultipleUploadListFragment();
                this.getSupportFragmentManager()
                        .beginTransaction()
                        .add(R.id.uploadsFragmentContainer, uploadsList, "uploadsList")
                        .commit();
            }
            setTitle(getResources().getQuantityString(R.plurals.multiple_uploads_title, photosList.size(), photosList.size()));

            Intent uploadServiceIntent = new Intent(getApplicationContext(), UploadService.class);
            uploadServiceIntent.setAction(UploadService.ACTION_START_SERVICE);
            startService(uploadServiceIntent);
            bindService(uploadServiceIntent, uploadServiceConnection, Context.BIND_AUTO_CREATE);
        }

    }


    @Override
    protected void onAuthFailure() {
        super.onAuthFailure();
        Toast failureToast = Toast.makeText(this, R.string.authentication_failed, Toast.LENGTH_LONG);
        failureToast.show();
        finish();
    }

    public void onBackStackChanged() {
        if(mediaDetails != null && mediaDetails.isVisible()) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        } else {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }
    }

}
