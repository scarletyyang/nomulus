// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.webdriver;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.abs;
import static java.util.stream.Collectors.joining;

import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;

/** Implementation of {@link WebDriverPlusScreenDiffer} that uses {@link ChromeDriver}. */
class ChromeWebDriverPlusScreenDiffer implements WebDriverPlusScreenDiffer {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String SCREENSHOT_DIR = "build/screenshots";
  private static final ChromeDriverService chromeDriverService = createChromeDriverService();

  private final WebDriver webDriver;
  private final String goldensPath;
  private final int maxColorDiff;
  private final int maxPixelDiff;
  private final List<ActualScreenshot> actualScreenshots;

  public ChromeWebDriverPlusScreenDiffer(String goldensPath, int maxColorDiff, int maxPixelDiff) {
    this.webDriver = createChromeDriver();
    this.goldensPath = goldensPath;
    this.maxColorDiff = maxColorDiff;
    this.maxPixelDiff = maxPixelDiff;
    this.actualScreenshots = Lists.newArrayList();
  }

  private WebDriver createChromeDriver() {
    ChromeOptions chromeOptions = new ChromeOptions();
    chromeOptions.setHeadless(true);
    // For Windows OS, this is required to let headless mode work properly
    chromeOptions.addArguments("disable-gpu");
    return new RemoteWebDriver(chromeDriverService.getUrl(), chromeOptions);
  }

  private static ChromeDriverService createChromeDriverService() {
    ChromeDriverService chromeDriverService =
        new ChromeDriverService.Builder().usingAnyFreePort().build();
    try {
      chromeDriverService.start();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Runtime.getRuntime().addShutdownHook(new Thread(chromeDriverService::stop));
    return chromeDriverService;
  }

  @AutoValue
  abstract static class ComparisonResult {
    abstract ActualScreenshot actualScreenshot();

    abstract boolean isConsideredSimilar();

    abstract boolean isMissingGoldenImage();

    abstract boolean isSizeDifferent();

    abstract int numDiffPixels();

    static Builder builder() {
      return new AutoValue_ChromeWebDriverPlusScreenDiffer_ComparisonResult.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setActualScreenshot(ActualScreenshot actualScreenshot);

      abstract Builder setIsConsideredSimilar(boolean isConsideredSimilar);

      abstract Builder setIsMissingGoldenImage(boolean isMissingGoldenImage);

      abstract Builder setIsSizeDifferent(boolean isSizeDifferent);

      abstract Builder setNumDiffPixels(int numDiffPixels);

      abstract ComparisonResult build();
    }
  }

  static class ScreenshotNotSimilarException extends RuntimeException {
    public ScreenshotNotSimilarException(String message) {
      super(message);
    }
  }

  @Override
  public WebDriver getWebDriver() {
    return webDriver;
  }

  @Override
  public void diffElement(String imageKey, WebElement element) {
  }

  @Override
  public void diffPage(String imageKey) {
  }

  @Override
  public void verifyAndQuit() {
    String errorMessage =
        actualScreenshots
            .parallelStream()
            .map(this::compareScreenshots)
            .map(
                result -> {
                  String imageName = result.actualScreenshot().getImageName();
                  String goldenImagePath = goldenImagePath(imageName);
                  Path persistedScreenshot = persistScreenshot(result.actualScreenshot());

                  if (result.isConsideredSimilar()) {
                    logger.atInfo().log(
                        String.format(
                            "Screenshot test for [%s] passed:\n"
                                + "  - golden image location: %s\n"
                                + "  - screenshot image location: %s",
                            imageName, goldenImagePath, persistedScreenshot.toAbsolutePath()));
                    return "";
                  } else {
                    String diffReason =
                        String.format("it differed by %d pixels", result.numDiffPixels());
                    if (result.isMissingGoldenImage()) {
                      diffReason = "the golden image was missing";
                    } else if (result.isSizeDifferent()) {
                      diffReason = "the size of image was different";
                    }

                    return String.format(
                        "Screenshot test for [%s] failed because %s:\n"
                            + "  - golden image location: %s\n"
                            + "  - screenshot image location: %s",
                        imageName,
                        diffReason,
                        result.isMissingGoldenImage() ? "missing" : goldenImagePath(imageName),
                        persistedScreenshot.toAbsolutePath());
                  }
                })
            .filter(message -> !message.isEmpty())
            .collect(joining("\n"));

    if (!errorMessage.isEmpty()) {
      errorMessage =
          String.format(
              "Following screenshot comparison comparisonInputs failed: \n%s", errorMessage);
      logger.atSevere().log(errorMessage);
      throw new ScreenshotNotSimilarException(errorMessage);
    }
  }

  private ActualScreenshot takeScreenshot(String imageKey) {
    checkArgument(webDriver instanceof TakesScreenshot);
    TakesScreenshot takesScreenshot = (TakesScreenshot) webDriver;
    return ActualScreenshot.create(imageKey, takesScreenshot.getScreenshotAs(OutputType.BYTES));
  }

  private ComparisonResult compareScreenshots(ActualScreenshot screenshot) {
    int totalPixels = screenshot.getWidth() * screenshot.getHeight();
    Optional<BufferedImage> maybeGoldenImage = loadGoldenImageByName(screenshot.getImageName());
    ComparisonResult.Builder commonBuilder =
        ComparisonResult.builder()
            .setActualScreenshot(screenshot)
            .setIsConsideredSimilar(false)
            .setIsMissingGoldenImage(false)
            .setIsSizeDifferent(false)
            .setNumDiffPixels(totalPixels);

    if (!maybeGoldenImage.isPresent()) {
      return commonBuilder.setIsMissingGoldenImage(true).build();
    }
    BufferedImage goldenImage = maybeGoldenImage.get();

    if ((screenshot.getWidth() != goldenImage.getWidth())
        || (screenshot.getHeight() != goldenImage.getHeight())) {
      return commonBuilder.setIsSizeDifferent(true).build();
    }

    int currPixelDiff = 0;
    for (int x = 0; x < screenshot.getWidth(); x++) {
      for (int y = 0; y < screenshot.getHeight(); y++) {
        Color screenshotColor = new Color(screenshot.getRGB(x, y));
        Color goldenImageColor = new Color(goldenImage.getRGB(x, y));
        int currColorDiff =
            IntStream.of(
                    abs(screenshotColor.getRed() - goldenImageColor.getRed()),
                    abs(screenshotColor.getGreen() - goldenImageColor.getGreen()),
                    abs(screenshotColor.getBlue() - goldenImageColor.getBlue()))
                .max()
                .getAsInt();
        if (currColorDiff > maxColorDiff) {
          currPixelDiff++;
        }
      }
    }
    commonBuilder.setNumDiffPixels(currPixelDiff);
    if (currPixelDiff <= maxPixelDiff) {
      commonBuilder.setIsConsideredSimilar(true);
    }

    return commonBuilder.build();
  }

  private Path persistScreenshot(ActualScreenshot screenshot) {
    File thisScreenshotDir = new File(SCREENSHOT_DIR, screenshot.getImageKey());
    thisScreenshotDir.mkdirs();
    File thisScreenshotFile =
        new File(
            thisScreenshotDir,
            Joiner.on(".").join(System.currentTimeMillis(), ActualScreenshot.IMAGE_FORMAT));
    screenshot.writeTo(thisScreenshotFile);
    return thisScreenshotFile.toPath();
  }

  private String goldenImagePath(String imageName) {
    return Paths.get(goldensPath, imageName).toString();
  }

  private Optional<BufferedImage> loadGoldenImageByName(String imageName) {
    File imageFile = new File(goldenImagePath(imageName));
    if (!imageFile.isFile()) {
      return Optional.empty();
    }
    try {
      return Optional.of(ImageIO.read(imageFile));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
