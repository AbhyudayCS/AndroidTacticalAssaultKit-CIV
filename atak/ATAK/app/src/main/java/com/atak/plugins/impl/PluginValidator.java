package com.atak.plugins.impl;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Base64;

import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.filesystem.HashingUtils;
import com.atakmap.net.AtakCertificateDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

class PluginValidator {

    private final static String TAG = "PluginValidator";

    private final static Map<String, String>  hashCache = new HashMap<>();

    private static final int INVALID = 0;
    private static final int VALID = 1;

    private PluginValidator() {}
    
    /**
     * Checks the app transparency signature and if valud compares the SHA256 checksums with the
     * dex files in the apk.
     * @param pkgname the package name to load the files from
     * @return true if the jwt is valid and the files pass the dex check
     */
    public static boolean checkAppTransparencySignature(final Context context, String pkgname, final String[] whitelist) {
        long start = SystemClock.elapsedRealtime();
        PackageInfo packageInfo;
        try {
            PackageManager pm = context.getPackageManager();
            packageInfo = pm.getPackageInfo(pkgname, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }

        File file = new File(packageInfo.applicationInfo.sourceDir);


        // The reason that we make use of a fragile hash cache is because people could change their
        // system time and install the plugin after they have already loaded a valid apk.    For a
        // super large plugin (200-300mb) the sha256 calculation can take up to 700ms on a S10.
        final String key = pkgname + "," + packageInfo.lastUpdateTime;
        String apkHash;
        synchronized (hashCache) {
            apkHash = hashCache.get(key);
            if (apkHash == null) {
                apkHash = HashingUtils.sha256sum(file);
                hashCache.put(key, apkHash);
                Log.d(TAG, "generated a fragile hash cache for: " + key);
            }
        }

        // Check the permanent cache to see if a plugin with a specific hash has been considered
        // valid or invalid.
        try {
            final byte[] recordedHash = AtakCertificateDatabase.getCertificate(pkgname);
            if (recordedHash != null) {
                String recordedHashString = new String(recordedHash, FileSystemUtils.UTF8_CHARSET);
                String[] vals = recordedHashString.split("\\.");
                if (vals.length > 1) {
                    if (vals[0].equals(apkHash)) {
                        Log.d(TAG, "previously validated signature and hashes for: " + pkgname +
                                " took: " + (SystemClock.elapsedRealtime() - start) + "ms");
                        return vals[1].equals(Integer.toString(VALID));
                    } else {
                        AtakCertificateDatabase.deleteCertificate(pkgname);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Make a determination if the plugin is considered valid or invalid.
        ZipFile zipFile = null;
        try {
             zipFile = new ZipFile(file);

            final ZipEntry jwtEntry = zipFile.getEntry("META-INF/code_transparency_signed.jwt");
            final InputStream jwtInputStream = zipFile.getInputStream(jwtEntry);
            final String jwt =
                    FileSystemUtils.copyStreamToString(jwtInputStream, true, FileSystemUtils.UTF8_CHARSET);

            byte[][] certder = new byte[whitelist.length][];
            for(int i = 0; i < whitelist.length; i++)
                certder[i] = ATAKUtilities.hexToBytes(whitelist[i]);


            final String headerpayload = jwt.substring(0, jwt.lastIndexOf("."));
            final String sig = jwt.substring(jwt.lastIndexOf(".")+1);
            final boolean v = check(Base64.decode(sig, Base64.NO_WRAP | Base64.URL_SAFE),
                    headerpayload.getBytes(), certder);

            //Log.d(TAG, "package: " + pkgname + " valid: " + v);
            if (v) {
                final String payloadString = new String(Base64.decode(headerpayload.split("\\.")[1],
                        Base64.NO_WRAP | Base64.URL_SAFE), FileSystemUtils.UTF8_CHARSET);
                final JSONObject  payload = new JSONObject(payloadString);
                final JSONArray codeRelatedFile = payload.getJSONArray("codeRelatedFile");
                for (int i = 0; i < codeRelatedFile.length(); ++i) {
                    JSONObject o = codeRelatedFile.getJSONObject(i);
                    String path = o.getString("path");
                    String sha256 = o.getString("sha256");

                    // dex files are at the root of the zip
                    // libraries are one level down.
                    ZipEntry dex = zipFile.getEntry(
                                path.replace("base/dex/", "")
                                    .replace("base/lib/", "lib/"));
                    InputStream inputStream = null;
                    String classesdexSha256;
                    try {
                        inputStream = zipFile.getInputStream(dex);
                        classesdexSha256 = HashingUtils.sha256sum(inputStream);
                    } finally {
                        if (inputStream != null)
                            inputStream.close();
                    }

                    if (!sha256.equals(classesdexSha256)) {
                        Log.d(TAG, "valid signature but invalid hashes for: " + pkgname + "@" + path +
                               " took: " + (SystemClock.elapsedRealtime() - start) + "ms");
                        AtakCertificateDatabase.saveCertificate(pkgname,
                                (apkHash + "." + INVALID).getBytes(FileSystemUtils.UTF8_CHARSET));
                        return false;
                    }
                }

                Log.d(TAG, "valid signature and hashes for: " + pkgname + " took: " + (SystemClock.elapsedRealtime() - start) + "ms");
                AtakCertificateDatabase.saveCertificate(pkgname,
                        (apkHash + "." + VALID).getBytes(FileSystemUtils.UTF8_CHARSET));
                return true;

            } else {
                Log.d(TAG, "invalid signature for: " + pkgname + " took: " + (SystemClock.elapsedRealtime() - start) + "ms");
                AtakCertificateDatabase.saveCertificate(pkgname,
                        (apkHash + "." + INVALID).getBytes(FileSystemUtils.UTF8_CHARSET));
            }



        } catch (Exception e) {
            Log.d(TAG, "error during validity check: " + pkgname, e);
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (Exception ignored) {}
            }
        }


        return false;
    }

    /**
     * Will clear the hash cache of any hashes related to the apk described by pkgName
     * @param pkgName the package name
     */
    static void invalidateHash(String pkgName) {
        synchronized (hashCache) {
            final Set<Map.Entry<String, String>> entries = hashCache.entrySet();
            final List<String> removal = new ArrayList<>();

            for (Map.Entry<String, String> entry : entries) {
                final String key = entry.getKey();
                if (key.startsWith(pkgName + ","))
                    removal.add(key);
            }
            for (String key : removal)
                hashCache.remove(key);
        }
    }

    private static boolean check(final byte[] signature, final byte[] message, byte[][] whitelist) throws CertificateException,
            NoSuchAlgorithmException, InvalidKeyException, SignatureException {

        Throwable err = null;
        for(byte[] certder : whitelist) {
            try {
                InputStream certstream = new ByteArrayInputStream(certder);
                Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(certstream);
                PublicKey key = cert.getPublicKey();

                //verify Signature
                Signature sig = Signature.getInstance("SHA256withRSA");
                sig.initVerify(key);
                sig.update(message);
                if (sig.verify(signature))
                    return true;
            } catch(Throwable t) {
                err = t;
            }
        }

        // if an error occurred, let it percolate up the callstack
        if(err instanceof CertificateException)
            throw (CertificateException) err;
        else if(err instanceof NoSuchAlgorithmException)
            throw (NoSuchAlgorithmException) err;
        else if(err instanceof InvalidKeyException)
            throw (InvalidKeyException) err;
        else if(err instanceof SignatureException)
            throw (SignatureException) err;
        return false;
    }

}