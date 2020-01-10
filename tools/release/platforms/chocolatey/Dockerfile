# Make sure we're on a version that supports symlinks, and that we're using
# powershell instead of cmd by default
FROM  mcr.microsoft.com/windows/servercore:1809
SHELL ["powershell", "-command"]
ARG version=
ARG timestamp=
ARG repository=facebook/buck

# Install chocolatey
RUN Set-ExecutionPolicy Bypass -Scope Process -Force; iex ((New-Object System.Net.WebClient).DownloadString('https://chocolatey.org/install.ps1'))

# Download enough to bootstrap a build
RUN choco install -y python2 git jdk8

# The JRE package that ant depends on seems to be somewhat spotty,
# install the jdk instead and use its java binary.
RUN choco install -y -i ant

# Clone buck
RUN git clone --branch v${env:version} --depth 1 https://github.com/${env:repository}.git c:\src

WORKDIR c:/src

# Bootstrap buck
RUN ant

# Build buck with buck
RUN ./bin/buck build -c buck.release_version=${env:version} -c buck.release_timestamp=${env:timestamp} buck

# This is filled in by build scripts
ADD Changelog.md.new c:/src/tools/release/platforms/chocolatey/Changelog.md

# Build the buck nupkg
RUN ./bin/buck build -c buck.release_version=${env:version} -c buck.release_timestamp=${env:timestamp} tools/release/platforms/chocolatey --out buck.nupkg
