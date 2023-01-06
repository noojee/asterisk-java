#! /usr/bin/env dcli

import 'dart:io';

import 'package:dcli/dcli.dart';

/// comment

void main() {
  int major = 0;
  int minor = 0;
  int rev = 0;
  var dir = dirname(Script.current.pathToScript);
  print(dir);
  read(join(dir, "pom.xml")).forEach((line) {
    if (line.contains("<releaseVersion>")) {
      line = line.replaceFirst("-SNAPSHOT", "");
      line = line.replaceFirst("-nj", "");
      var parts = line.split(".");
      if (parts.length != 3) {
        exit(1);
      }
      major = int.parse(parts[0].split(">")[1]);
      minor = int.parse(parts[1]);
      rev = int.parse(parts[2].split("<")[0]);
    }
  });

  var postFix = "-nj";
  rev = 0;
  minor++;

  String version = "$major.$minor.$rev$postFix";

  replacePomVersion(join(dir, "pom.xml"), version);
  replaceReadMeVersion(join(dir, "README.md"), version);

  'git pull'.run;
  'git add .'.run;
  'git commit -m "for version $version"'.run;
  'git tag -a $version -m "$version"'.run;
  'git push origin'.run;
  'git push origin tag $version'.run;
}

void replaceReadMeVersion(String path, String version) {
  var tmp = '$path.tmp';
  if (exists(tmp)) {
    delete(tmp);
  }
  read(path).forEach((line) {
    if (line.contains("<version>")) {
      line = "	  <version>$version</version>";
    }
    tmp.append(line);
  });
  move(path, '$path.bak');
  move(tmp, path);
  delete('$path.bak');
}

void replacePomVersion(String path, String version) {
  var tmp = '$path.tmp';
  if (exists(tmp)) {
    delete(tmp);
  }
  read(path).forEach((line) {
    if (line.contains("<releaseVersion>")) {
      line = "        <releaseVersion>$version</releaseVersion>";
    }
    tmp.append(line);
  });
  move(path, '$path.bak');
  move(tmp, path);
  delete('$path.bak');
}
