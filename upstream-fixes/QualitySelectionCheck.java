import app.revanced.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.revanced.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;
import java.lang.reflect.Method;

/** Exercises the compiled upstream method with real generated protobuf messages. */
public class QualitySelectionCheck {
    private static Method selector;
    private static int checks;
    private static Format video(String codec, String quality) {
        return Format.newBuilder().setMimeType("video/mp4; codecs=\"" + codec + "\"")
            .setQualityLabel(quality).setUrl("https://example.com/" + codec + "/" + quality).build();
    }
    private static StreamingData select(StreamingData input) throws Exception {
        return (StreamingData) selector.invoke(null, input);
    }
    private static void check(boolean pass, String reason) {
        if (!pass) throw new AssertionError(reason);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        selector = Class.forName("app.revanced.extension.shared.spoof.requests.StreamingDataRequest")
            .getDeclaredMethod("preferMultipleAvcQualities", StreamingData.class);
        selector.setAccessible(true);
        Format audio = Format.newBuilder().setMimeType("audio/webm; codecs=\"opus\"")
            .setUrl("https://example.com/audio").build();
        Format low = video("avc1", "360p"), high = video("avc1", "1080p"), vp9 = video("vp9", "360p");
        StreamingData limited = StreamingData.newBuilder().addAdaptiveFormats(low).addAdaptiveFormats(high)
            .addAdaptiveFormats(vp9).addAdaptiveFormats(audio).setServerAbrStreamingUrl("https://example.com/sabr")
            .addFormats(video("avc1", "720p")).build();
        StreamingData result = select(limited);
        check(result.getAdaptiveFormatsCount() == 3, "Single VP9 quality must not hide the AVC ladder");
        check(result.getAdaptiveFormatsList().contains(low) && result.getAdaptiveFormatsList().contains(high), "Keep all AVC qualities");
        check(!result.getAdaptiveFormatsList().contains(vp9), "Remove incomplete VP9 ladder");
        check(result.getAdaptiveFormatsList().contains(audio), "Keep audio unchanged");
        check(result.getFormatsList().equals(limited.getFormatsList()), "Keep progressive formats unchanged");
        check(result.getServerAbrStreamingUrl().equals(limited.getServerAbrStreamingUrl()), "Preserve other streaming fields");
        StreamingData complete = limited.toBuilder().addAdaptiveFormats(video("vp9", "1080p")).build();
        check(select(complete).equals(complete), "Keep complete VP9 ladders");
        StreamingData vp9Only = StreamingData.newBuilder().addAdaptiveFormats(vp9).addAdaptiveFormats(audio).build();
        check(select(vp9Only).equals(vp9Only), "Keep VP9 when no AVC alternative exists");
        StreamingData equal = StreamingData.newBuilder().addAdaptiveFormats(vp9).addAdaptiveFormats(low).build();
        check(select(equal).equals(equal), "Keep VP9 when AVC has no additional quality");
        StreamingData duplicate = limited.toBuilder().addAdaptiveFormats(vp9).build();
        check(select(duplicate).getAdaptiveFormatsCount() == 3, "Count distinct quality labels, not duplicate formats");
        StreamingData noLabels = StreamingData.newBuilder().addAdaptiveFormats(video("vp9", ""))
            .addAdaptiveFormats(video("avc1", "")).build();
        check(select(noLabels).equals(noLabels), "Do not filter unlabeled formats");
        System.out.println(checks + " checks passed against the compiled upstream quality-selection method.");
    }
}
