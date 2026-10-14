package zip.linuxaddict.georestrict.it;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import zip.linuxaddict.georestrict.NetworkUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
class NetworkLiteralIT {
    @Test
    void nonAsciiDigitsAndHostnamesNeverReachResolver() throws Exception {
        try (MockedStatic<InetAddress> resolver = mockStatic(InetAddress.class)) {
            resolver.when(() -> InetAddress.getByName(anyString()))
                .thenThrow(new AssertionError("Rejected IP text reached the hostname resolver"));
            for (String text : new String[]{"١٢٧.٠.٠.١", "１２７.０.０.１", "8.8.8.٨", "localhost"}) {
                assertFalse(NetworkUtils.isLocalIp(text), text);
                assertFalse(NetworkUtils.isPrivateIp(text), text);
                assertFalse(NetworkUtils.isValidPublicIp(text), text);
            }
            resolver.verifyNoInteractions();
        }
    }
}
