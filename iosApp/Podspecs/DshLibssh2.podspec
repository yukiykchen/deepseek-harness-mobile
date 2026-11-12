Pod::Spec.new do |s|
  s.name = 'DshLibssh2'
  s.version = '1.11.1'
  s.summary = 'Pinned upstream libssh2 built from source for DSH Apple hosts.'
  s.homepage = 'https://github.com/libssh2/libssh2'
  s.license = { :type => 'BSD-3-Clause', :file => 'COPYING' }
  s.author = 'libssh2 contributors'
  s.source = { :http => 'https://www.libssh2.org/download/libssh2-1.11.1.tar.gz',
               :sha256 => 'd9ec76cbe34db98eec3539fe2c899d26b0c837cb3eb466a56b0f109cabf658f7' }
  s.ios.deployment_target = '14.1'
  s.osx.deployment_target = '10.15'
  s.requires_arc = false
  s.static_framework = true
  s.dependency 'OpenSSL-Universal', '3.6.2000'
  s.libraries = 'z'
  # The compilation units are upstream src/Makefile.inc CSOURCES. Backend .c files are included internally.
  units = %w[agent bcrypt_pbkdf channel comp chacha cipher-chachapoly crypt crypto global hostkey keepalive
             kex knownhost mac misc packet pem poly1305 publickey scp session sftp transport userauth userauth_kbd_packet version]
  s.source_files = ['include/*.h', 'src/*.h', 'dsh-config/*.h'] + units.map { |name| "src/#{name}.c" }
  s.preserve_paths = 'src/*.c'
  s.public_header_files = 'include/*.h'
  s.header_mappings_dir = 'include'
  s.pod_target_xcconfig = {
    'GCC_PREPROCESSOR_DEFINITIONS' => '$(inherited) HAVE_CONFIG_H LIBSSH2_OPENSSL LIBSSH2_HAVE_ZLIB',
    'HEADER_SEARCH_PATHS' => '$(inherited) "${PODS_TARGET_SRCROOT}/src" "${PODS_TARGET_SRCROOT}/dsh-config"',
  }
  # Apple SDK capabilities shared by iOS 14.1+ and macOS 10.15+. No generated binary is checked in.
  s.prepare_command = <<-CMD
    ruby -rfileutils -e '
      FileUtils.mkdir_p("dsh-config")
      features = %w[UNISTD_H INTTYPES_H SYS_SELECT_H SYS_UIO_H SYS_SOCKET_H SYS_IOCTL_H SYS_TIME_H SYS_UN_H
                    ARPA_INET_H NETINET_IN_H GETTIMEOFDAY STRTOLL SNPRINTF MEMSET_S SELECT O_NONBLOCK FIONBIO]
      File.write("dsh-config/libssh2_config.h", features.map { |name| "#define HAVE_" + name + " 1" }.join("\\n") + "\\n")
    '
  CMD
end
