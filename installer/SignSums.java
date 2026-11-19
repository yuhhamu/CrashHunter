import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class SignSums {
    public static void main(String[] a) throws Exception {
        if (a.length == 3 && a[0].equals("--generate")) {
            Path key = Path.of(a[1]);
            if (Files.exists(key)) throw new IllegalStateException("key exists: " + key);
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Files.createDirectories(key.getParent());
            Files.writeString(key, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            Files.writeString(Path.of(a[2]), Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            System.out.println("generated");
            return;
        }
        if (a.length != 2) throw new IllegalArgumentException("usage: --generate <key> <pub> | <key> <file>");
        var priv = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(Path.of(a[0])).trim())));
        var s = Signature.getInstance("Ed25519");
        s.initSign(priv);
        s.update(Files.readAllBytes(Path.of(a[1])));
        Files.writeString(Path.of(a[1] + ".sig"), Base64.getEncoder().encodeToString(s.sign()));
        System.out.println("signed");
    }
}
