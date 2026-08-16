FROM eclipse-temurin:21-jdk-jammy@sha256:55fb9bf738f5d9b4a6c01b39337e3070d3e27370dd3c478fd1d5d3cd2233c6d8

ARG ANDROID_CMDLINE_TOOLS_BUILD=15859902

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/emulator:${PATH}

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        libasound2 \
        libc6 \
        libdbus-1-3 \
        libfontconfig1 \
        libgcc-s1 \
        libgl1 \
        libnss3 \
        libpulse0 \
        libstdc++6 \
        libx11-6 \
        libx11-xcb1 \
        libxcb1 \
        libxcomposite1 \
        libxcursor1 \
        libxdamage1 \
        libxext6 \
        libxfixes3 \
        libxi6 \
        libxkbcommon0 \
        libxkbcommon-x11-0 \
        socat \
        unzip \
        zlib1g \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && curl --fail --location --retry 3 \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_BUILD}_latest.zip" \
        --output /tmp/android-commandline-tools.zip \
    && unzip -q /tmp/android-commandline-tools.zip -d /tmp/android-commandline-tools \
    && mv /tmp/android-commandline-tools/cmdline-tools "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm -rf /tmp/android-commandline-tools /tmp/android-commandline-tools.zip

RUN yes | sdkmanager --licenses >/dev/null \
    && sdkmanager \
        "build-tools;36.0.0" \
        "emulator" \
        "platform-tools" \
        "platforms;android-36.1" \
        "system-images;android-36;google_apis;x86_64"

RUN groupadd --gid 1000 android \
    && useradd --uid 1000 --gid 1000 --create-home --shell /bin/bash android \
    && mkdir -p /home/android/.android/avd /home/android/.gradle \
    && chown -R android:android /home/android

COPY emulator-entrypoint.sh /opt/nextgallery/bin/emulator-entrypoint.sh

RUN chmod 0755 /opt/nextgallery/bin/emulator-entrypoint.sh

USER android

RUN printf 'no\n' | avdmanager create avd \
        --force \
        --name nextgallery-api36 \
        --package "system-images;android-36;google_apis;x86_64"

WORKDIR /workspace

CMD ["/bin/bash"]
