package com.wifisecure.wifisecure;

import android.accounts.AccountManager;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.common.AccountPicker;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.wifisecure.wifisecure.openvpn.DisconnectVPN;
import com.wifisecure.wifisecure.openvpn.OpenVPNService;
import com.wifisecure.wifisecure.openvpn.ProfileManager;
import com.wifisecure.wifisecure.openvpn.VPNLaunchHelper;
import com.wifisecure.wifisecure.openvpn.VpnProfile;
import com.wifisecure.wifisecure.openvpn.VpnStatus;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class MainActivity extends AppCompatActivity {
    private Button secureButton;
    private Button inviteButton;
    ProgressDialog progress;

    protected OpenVPNService mService;

    View.OnClickListener secure = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            secure();
        }
    };

    View.OnClickListener unsecure = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            unsecure();
        }
    };

    private VpnProfile profile;

    private final int   LISTEN_PORT = 11445,
                        TUNNEL_PORT = 443;
    private final String TUNNEL_HOST = "wifisecure.tk";

    private int START_VPN_DIALOG = 1;
    private final int REQUEST_CODE_EMAIL = 2;
    private final int PLAY_SERVICES_ERROR = 3;

    private boolean granted = false;

    private Process stunnelProcess;
    private String ipAddress = "";
    private String serial;

    private String accountName;
    private boolean isReady = true;
    private boolean binded = false;
    private Boolean vpnOn = new Boolean(true);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (android.os.Build.VERSION.SDK_INT >= 23)
            checkPermissions();
        else
            granted = true;

        final Context context = this;

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (!granted) {
                    try {
                        Thread.sleep(5000);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        secureButton = (Button) findViewById(R.id.secure);
                        inviteButton = (Button) findViewById(R.id.invite);
                        progress = new ProgressDialog(context);
                        serial = getDeviceID();

                        profile = getProfileManager().getProfileByName("WifiSecure");

                        getAccount();

                        Intent intent = getIntent();
                        if (intent != null && intent.getAction() == OpenVPNService.DISCONNECT_VPN) {
                            Intent bindIntent = new Intent(context, OpenVPNService.class);
                            bindIntent.setAction(OpenVPNService.START_SERVICE);
                            boolean result = bindService(bindIntent, connection, BIND_AUTO_CREATE);
                            if (!result)
                                throw new RuntimeException("Could not rebind to disconnect.");
                            unsecure();
                        }
                    }
                });
            }
        }).start();
    }

    public void secure(View v) {
        secure();
    }

    public void secure() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized(vpnOn) {
                        String request = "http://" + ipAddress + "/vpnapi/status.php";
                        URL url = new URL(request);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        InputStream input = conn.getInputStream();
                        StringBuilder out = new StringBuilder();
                        byte[] buffer = new byte[10240];
                        try {
                            for (int length = 0; (length = input.read(buffer)) > 0; ) {
                                out.append(new String(buffer, 0, length));
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                        JSONObject obj = new JSONObject(out.toString());
                        vpnOn.notify();
                        vpnOn = obj.getString("status").equals("true");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
        synchronized(vpnOn) {
            try {
                vpnOn.wait();
                if (vpnOn == true) {
                    secureButton.setEnabled(false);
                    progress.setTitle("Securing");
                    progress.setMessage("Please wait...");
                    progress.show();
                    startStunnel();
                    startVPN();
                } else {
                    Toast toast = Toast.makeText(this, "Service is not currently on. Please try again between 6AM and 4PM, Monday-Friday", Toast.LENGTH_LONG);
                    toast.show();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void unsecure() {
        secureButton.setEnabled(false);
        progress.setTitle("Closing.");
        progress.setMessage("Please wait...");
        progress.show();
        try {
            stopVPN();
        } catch (Exception e) {
            e.printStackTrace();
        }

        update(false);
    }

    public void update(boolean secured) {
        secureButton.setEnabled(true);
        inviteButton.setVisibility(View.VISIBLE);
        progress.dismiss();
        if (secured) {
            secureButton.setText("Unsecure Me");

            secureButton.setOnClickListener(unsecure);
        } else {
            secureButton.setText("Secure Me!");

            secureButton.setOnClickListener(secure);
        }
    }


    private void startVPN() {
        Intent intent = VpnService.prepare(this);
        if (intent != null)
            startActivityForResult(intent, START_VPN_DIALOG);
        else
            onActivityResult(START_VPN_DIALOG, RESULT_OK, intent);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (VpnStatus.mLaststate.equals("NOPROCESS"))
                        Thread.sleep(500);

                    while (!VpnStatus.mLaststate.equals("CONNECTED") && !VpnStatus.mLaststate.equals("NOPROCESS")) {
                        Thread.sleep(500);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (VpnStatus.mLaststate.equals("NOPROCESS")) {
                            progress.dismiss();
                            secureButton.setEnabled(true);
                            inviteButton.setVisibility(View.VISIBLE);
                            stopService(new Intent(mService, OpenVPNService.class));
                            binded = false;
                            Toast toast = Toast.makeText(getApplicationContext(), "Could not secure connection. Are you connected to the internet?", Toast.LENGTH_LONG);
                            toast.show();
                        } else {
                            update(true);
                        }
                    }
                });
            }
        }).start();
    }

    private void stopVPN() {
        try {
            mService.getManagement().stopVPN();
            binded = false;
            Intent intent = new Intent(this, DisconnectVPN.class);
            startActivityForResult(intent, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == START_VPN_DIALOG) {
            if (resultCode == RESULT_OK) {
                try {
                    FileWriter cfg = new FileWriter(VPNLaunchHelper.getConfigFilePath(this));
                    cfg.write(profile.getConfigFile(this, false));
                    cfg.flush();
                    cfg.close();
                } catch (IOException e) {
                    VpnStatus.logException(e);
                }
                Intent intent = new Intent(this, OpenVPNService.class);
                startService(intent);

                intent = new Intent(this, OpenVPNService.class);
                intent.setAction(OpenVPNService.START_SERVICE);

                boolean result = bindService(intent, connection, Context.BIND_AUTO_CREATE);
                if (!result)
                    throw new RuntimeException("Unable to bind with service.");
            } else {
            }
        } else if (requestCode == REQUEST_CODE_EMAIL && resultCode == RESULT_OK) {
            accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            try {
                FileOutputStream out = this.openFileOutput("email.saved", Context.MODE_PRIVATE);
                out.write(accountName.getBytes());
                initializeApp();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void getAccount() {
        File file = new File(getFilesDir().getAbsolutePath() + "/email.saved");
        if (file.exists()) {
            accountName = readFile("email.saved");
            initializeApp();
        } else {
            GoogleApiAvailability availability = GoogleApiAvailability.getInstance();
            int playAvailable = availability.isGooglePlayServicesAvailable(this);
            if (playAvailable == ConnectionResult.SUCCESS) {
                Intent intent = AccountPicker.newChooseAccountIntent(null, null,
                        new String[]{GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE}, false, null, null, null, null);
                startActivityForResult(intent, REQUEST_CODE_EMAIL);
            } else {
                Dialog dialog = availability.getErrorDialog(this, playAvailable, PLAY_SERVICES_ERROR);
                dialog.show();
            }
        }
    }

    private void initializeApp() {
        progress.setTitle("Initializing");
        progress.setMessage("Please wait...");
        progress.show();

        final Context context = this;

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ipAddress = getIPAddress();

                    if (!(new File(getFilesDir().getAbsolutePath() + "/stunnel").exists())) {
                        File stunnel = downloadFile(getApplicationContext(), "http://" + ipAddress + "/vpnapi/stunnel/stunnel", "stunnel");
                        if (!stunnel.setExecutable(true))
                            throw new RuntimeException("Could not set stunnel to executable");
                    }

                    if (downloadFile(getApplicationContext(), "http://" + ipAddress + "/vpnapi/stunnel/stunnel.conf", "stunnel-template.conf") == null) {
                        isReady = false;

                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                Toast toast = Toast.makeText(context, "Can not connect.", Toast.LENGTH_SHORT);
                                toast.show();
                            }
                        });
                    } else {
                        if (profile == null) {
                            String out = postGetLink();
                            JSONObject data = new JSONObject(out);
                            String success = data.getString("success");
                            final String message = data.getString("message");

                            if (!(success.equals("true"))) {
                                isReady = false;
                                new Handler(Looper.getMainLooper()).post(new Runnable() {
                                    @Override
                                    public void run() {
                                        final Toast toast = Toast.makeText(context, message, Toast.LENGTH_LONG);
                                        toast.show();
                                    }
                                });
                            } else {
                                isReady = true;
                                while ((new JSONObject(postGetLink())).getString("available").equals("false"))
                                    Thread.sleep(200);

                                String params  = "serial="+serial+"&email="+accountName+"&file=";
                                String client = serial;
                                if (!(new File(getFilesDir().getAbsolutePath() + "/ca.crt").exists())) {
                                    downloadFilePost(context, "http://" + ipAddress + "/vpnapi/download.php", "ca.crt", params + "ca.crt");
                                }
                                String caFileContents = readFile("ca.crt");

                                String keyname = client + ".key";
                                if (!(new File(getFilesDir().getAbsolutePath() + "/" + keyname).exists())) {
                                    downloadFilePost(context, "http://" + ipAddress + "/vpnapi/download.php", keyname, params + keyname);
                                }
                                String clientKeyFileContents = readFile(keyname);

                                Thread.sleep(1000);
                                String certname = client + ".crt";
                                if (!(new File(getFilesDir().getAbsolutePath() + "/" + certname).exists())) {
                                    downloadFilePost(context, "http://" + ipAddress + "/vpnapi/download.php", certname, params + certname);
                                }
                                String clientCertFileContents = readFile(certname);

                                ProfileManager pm = getProfileManager();
                                profile = new VpnProfile("WifiSecure");
                                profile.mUseLzo = true;
                                profile.mServerName = "127.0.0.1";
                                profile.mServerPort = "1144";

                                profile.mUseUdp = false;
                                profile.mConnectRetryMax = "5";
                                profile.mConnectRetry = "5";
                                profile.mUseDefaultRoute = true;
                                profile.mUseDefaultRoutev6 = true;
                                profile.mUsePull = true;
                                profile.mPersistTun = true;

                                profile.mConnections[0].mEnabled = true;
                                profile.mConnections[0].mServerName = "127.0.0.1";
                                profile.mConnections[0].mServerPort = "1144";
                                profile.mConnections[0].mUseUdp = false;

                                profile.mCaFilename = "[[NAME]]ca.crt[[INLINE]]" + caFileContents;
                                profile.mClientKeyFilename = "[[NAME]]" + client + ".key[[INLINE]]" + clientKeyFileContents;
                                profile.mClientCertFilename = "[[NAME]]" + client + ".cert[[INLINE]]" + clientCertFileContents;

                                pm.addProfile(profile);
                                pm.saveProfileList(context);
                                pm.saveProfile(context, profile);
                            }
                        }
                    }
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isReady) {
                                secureButton.setEnabled(true);
                                inviteButton.setVisibility(View.VISIBLE);
                            }
                            progress.dismiss();
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        thread.start();
    }

    private ProfileManager getProfileManager() {
        return ProfileManager.getInstance(this);
    }

    private String getPhoneNumber() {
        TelephonyManager tMgr = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        return tMgr.getLine1Number();
    }

    private String readFile(String name) {
        try {
            Scanner scanner = new Scanner(openFileInput(name));
            String text = scanner.useDelimiter("\\A").next();
            scanner.close();
            return text;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private File downloadFile(final Context context, final String url, final String filename) {
        try {
            URL website = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) website.openConnection();

            InputStream input = connection.getInputStream();
            FileOutputStream output = context.openFileOutput(filename, Context.MODE_PRIVATE);

            byte data[] = new byte[4096];
            int count;
            while ((count = input.read(data)) != -1) {
                output.write(data, 0, count);
            }
            output.close();

            return new File(getFilesDir().getAbsolutePath() + "/" + filename);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private File downloadFilePost(final Context context, final String request, final String filename, String params) {
        try {
            byte[] postData = params.getBytes(Charset.forName("UTF-8"));
            int postDataLength = postData.length;
            URL url = new URL(request);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("charset", "utf-8");
            conn.setRequestProperty("Content-Length", Integer.toString(postDataLength));
            conn.setUseCaches(false);
            DataOutputStream wr = new DataOutputStream(conn.getOutputStream());
            try {
                wr.write(postData);
            } catch (Exception e) {
                e.printStackTrace();
            }
            wr.flush();
            wr.close();
            InputStream input = conn.getInputStream();
            FileOutputStream output = context.openFileOutput(filename, Context.MODE_PRIVATE);

            byte data[] = new byte[4096];
            int count;
            while ((count = input.read(data)) != -1) {
                output.write(data, 0, count);
            }
            input.close();
            output.close();

            return new File(getFilesDir().getAbsolutePath() + "/" + filename);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (binded)
            unbindService(connection);
    }

    private void startStunnel() {
        try {
            makeStunnelConf();

            String path = getFilesDir().getAbsolutePath() + "/";
            String[] command = {path + "stunnel", path + "stunnel.conf"};
            ProcessBuilder p = new ProcessBuilder(command);
            stunnelProcess = p.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void makeStunnelConf() {
        String content = readFile("stunnel-template.conf");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized(ipAddress) {
                        String ip = getIPAddress();
                        ipAddress.notify();
                        ipAddress = ip;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new RuntimeException("FAILED TO GET IP");
                }
            }
        }).start();

        synchronized(ipAddress) {
            try {
                ipAddress.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
                throw new RuntimeException("Darn. Got interrupted");
            }

            content = content.replaceAll("IP_GOES_HERE", ipAddress);

            try {
                FileOutputStream output = this.openFileOutput("stunnel.conf", Context.MODE_PRIVATE);
                output.write(content.getBytes());
                output.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private String getDeviceID() {
        final String deviceId = ((TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE)).getDeviceId();
        if (deviceId != null) {
            return deviceId;
        } else {
            return android.os.Build.SERIAL;
        }
    }

    private String postGetLink() {
        try {
            String phone = getPhoneNumber();

            String urlParameters = "serial=" + serial + "&email=" + accountName;
            if (phone != null) urlParameters += "&phone=" + phone;

            byte[] postData = urlParameters.getBytes(Charset.forName("UTF-8"));
            int postDataLength = postData.length;
            String request = "http://" + ipAddress + "/vpnapi/getlinks.php";
            URL url = new URL(request);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("charset", "utf-8");
            conn.setRequestProperty("Content-Length", Integer.toString(postDataLength));
            conn.setUseCaches(false);
            DataOutputStream wr = new DataOutputStream(conn.getOutputStream());
            try {
                wr.write(postData);
            } catch (Exception e) {
                e.printStackTrace();
            }
            wr.flush();
            wr.close();
            InputStream input = conn.getInputStream();
            StringBuilder out = new StringBuilder();
            byte[] buffer = new byte[10240];
            try {
                for (int length = 0; (length = input.read(buffer)) > 0; ) {
                    out.append(new String(buffer, 0, length));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            input.close();
            return out.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            OpenVPNService.LocalBinder binder = (OpenVPNService.LocalBinder) service;
            mService = binder.getService();
            mService.stunnelProcess = stunnelProcess;
            binded = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            binded = false;
        }
    };

    public void inviteAlert(View v) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        LinearLayout layout = new LinearLayout(this);
        LinearLayout.LayoutParams parms = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(parms);

        layout.setGravity(Gravity.CLIP_VERTICAL);
        layout.setPadding(2, 2, 2, 2);

        TextView tv = new TextView(this);
        tv.setText("Invite User");
        tv.setPadding(40, 40, 40, 40);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(20);

        final EditText et = new EditText(this);
        String etStr = et.getText().toString();
        TextView tv1 = new TextView(this);
        tv1.setHint("Email");

        LinearLayout.LayoutParams tv1Params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tv1Params.bottomMargin = 5;
        layout.addView(tv1, tv1Params);
        layout.addView(et, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        builder.setView(layout);
        builder.setTitle("Invite User");
        builder.setCustomTitle(tv);

        // Setting Negative "Cancel" Button
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                dialog.cancel();
            }
        });

        builder.setPositiveButton("Send Invite", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                Toast toast = Toast.makeText(getApplicationContext(), "Invite Sent!", Toast.LENGTH_LONG);
                toast.show();

                sendInvite(et.getText().toString());
            }
        });

        builder.show();
    }

    private String getIPAddress() {
        try {
            InetAddress address = InetAddress.getByName(new URL("http://wifisecure.tk").getHost());
            return address.getHostAddress();
        } catch (Exception e) {
            e.printStackTrace();
            try {
                URL website = new URL("http://labibij.hulk.osd.wednet.edu/apis/get_ip.php");
                HttpURLConnection connection = (HttpURLConnection) website.openConnection();

                InputStream input = connection.getInputStream();

                byte data[] = new byte[1000];
                int count = input.read(data);
                return new String(data, 0, count);
            } catch (Exception err) {
                err.printStackTrace();
            }
        }
        return null;
    }

    private void sendInvite(final String invited) {
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String charset = Charset.forName("UTF-8").name();
                        String parameters = String.format("serial=%s&email=%s&invited=%s",
                                URLEncoder.encode(serial, charset),
                                URLEncoder.encode(accountName, charset),
                                URLEncoder.encode(invited, charset));
                        byte[] postData = parameters.getBytes(Charset.forName("UTF-8"));
                        int postDataLength = postData.length;
                        String request = "http://" + ipAddress + "/vpnapi/invite.php";
                        URL url = new URL(request);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setDoOutput(true);
                        conn.setInstanceFollowRedirects(false);
                        conn.setRequestMethod("POST");
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        conn.setRequestProperty("charset", "utf-8");
                        conn.setRequestProperty("Content-Length", Integer.toString(postDataLength));
                        conn.setUseCaches(false);
                        DataOutputStream wr = new DataOutputStream(conn.getOutputStream());
                        try {
                            wr.write(postData);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        wr.flush();
                        wr.close();

                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        br.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }).start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void checkPermissions() {
        try {
            List<String> requesting = new ArrayList<String>();
            for (String permission : getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    requesting.add(permission);
                }
            }
            if (requesting.size() > 0) {
                String[] requestingArr = requesting.toArray(new String[requesting.size()]);
                ActivityCompat.requestPermissions(this, requestingArr, 0);
            } else {
                granted = true;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 0: {
                if (grantResults.length == permissions.length && checkAllGranted(grantResults)) {
                    granted = true;
                    return;
                } else {
                    Toast toast = Toast.makeText(this, "To use the app, I need all the requested permissions. Please enable them in your app settings. Thank you for your understanding.", Toast.LENGTH_LONG);
                    toast.show();
                    checkPermissions();
                }
                return;
            }
        }
    }

    private boolean checkAllGranted(int[] results) {
        for (int result : results) {
            if (result != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }
}