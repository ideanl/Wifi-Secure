package ideanlabib.com.wifisecure;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Created by idean on 2/6/16.
 */
public class Connector extends Thread {
    private int listenPort;
    private String tunnelHost;
    private int tunnelPort;
    private MainActivity activity;

    private SSLSocketFactory sslSocketFactory;
    public ServerSocket ss;

    private int sessionid = 0;

    public Connector(int listenPort, String tunnelHost, int tunnelPort, MainActivity activity) {
        this.listenPort = listenPort;
        this.tunnelHost = tunnelHost;
        this.tunnelPort = tunnelPort;
        this.activity = activity;
    }

    public void run() {
        try {
            ss = new ServerSocket(listenPort, 50, InetAddress.getLocalHost());
            Log.d("Connector", "Listening for connections on " + InetAddress.getLocalHost().getHostAddress() + ":" +
                    +this.listenPort + " ...");
        } catch (Exception e) {
            Log.d("Connector", "Error setting up listening socket: " + e.toString());
            return;
        }
        while (true) {
            try {
                Thread fromBrowserToServer = null;
                Thread fromServerToBrowser = null;

                if (isInterrupted()) {
                    Log.d("Connector", "Interrupted server thread, closing sockets...");
                    ss.close();
                    return;
                }
                // accept the connection from my client
                Socket sc = null;
                try {
                    sc = ss.accept();
                    sessionid++;
                } catch (SocketException e) {
                    Log.d("SSLDroid", "Accept failure: " + e.toString());
                }

                Socket st = null;
                try {
                    final SSLSocketFactory sf = getSocketFactory();
                    st = (SSLSocket) sf.createSocket(this.tunnelHost, this.tunnelPort);
                    setSNIHost(sf, (SSLSocket) st, this.tunnelHost);
                    ((SSLSocket) st).startHandshake();
                } catch (IOException e) {
                    Log.d("Connector", "SSL failure: " + e.toString());
                    return;
                }
                catch (Exception e) {
                    Log.d("Connector", "SSL failure: " + e.toString());
                    if (sc != null)
                    {
                        sc.close();
                    }
                    return;
                }

                if (sc == null || st == null) {
                    Log.d("Connector", "Trying socket operation on a null socket, returning");
                    return;
                }
                Log.d("Connector", "Tunnelling port "
                        + listenPort + " to port "
                        + tunnelPort + " on host "
                        + tunnelHost + " ...");

                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        activity.update(true);
                    }
                });

                // relay the stuff through
                fromBrowserToServer = new Relay(
                        this, sc.getInputStream(), st.getOutputStream(), "client", sessionid);
                fromServerToBrowser = new Relay(
                        this, st.getInputStream(), sc.getOutputStream(), "server", sessionid);

                fromBrowserToServer.start();
                fromServerToBrowser.start();

            } catch (IOException ee) {
                Log.d("Connector", "Ouch: " + ee.toString());
            }
        }
    }

    public final SSLSocketFactory getSocketFactory() {
        if (sslSocketFactory == null) {
            try {
                KeyManagerFactory keyManagerFactory = null;
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, trustAllCerts,
                        new SecureRandom());
                sslSocketFactory = context.getSocketFactory();
            } catch (KeyManagementException e) {
                Log.d("Connector", "No SSL algorithm support: " + e.toString());
            } catch (NoSuchAlgorithmException e) {
                Log.d("Connector", "No common SSL algorithm found: " + e.toString());
            }
        }
        return sslSocketFactory;
    }

    TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
                public void checkClientTrusted(
                        java.security.cert.X509Certificate[] certs, String authType) {
                }
                public void checkServerTrusted(
                        java.security.cert.X509Certificate[] certs, String authType) {
                }
            }
    };

    private void setSNIHost(final SSLSocketFactory factory, final SSLSocket socket, final String hostname) {
        if (factory instanceof android.net.SSLCertificateSocketFactory && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            ((android.net.SSLCertificateSocketFactory)factory).setHostname(socket, hostname);
        } else {
            try {
                socket.getClass().getMethod("setHostname", String.class).invoke(socket, hostname);
            } catch (Throwable e) {
                // ignore any error, we just can't set the hostname...
            }
        }
    }
}
