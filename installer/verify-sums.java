import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class VerifySums {
    static final String KEY = "MCowBQYDK2VwAyEAqbphWCLzOjftvUptjktkQJ+8iQ9v+067jNB3nAbTLBk=";
    public static void main(String[] a) throws Exception {
        var k = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(KEY)));
        var s = Signature.getInstance("Ed25519");
        s.initVerify(k);
        s.update(Files.readAllBytes(Path.of(a[0])));
        System.out.println(s.verify(Base64.getDecoder().decode(Files.readString(Path.of(a[1])).trim())));
    }
}
