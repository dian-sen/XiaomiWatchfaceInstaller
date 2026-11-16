class TeamMember {
  const TeamMember({
    required this.name,
    required this.role,
    required this.avatarAsset,
    required this.githubUrl,
  });

  final String name;
  final TeamRole role;
  final String avatarAsset;
  final String githubUrl;
}

enum TeamRole {
  mainDeveloperArchitecture,
  zeppOSImplementation,
  harmonyOSPorting,
  deviceConnectionCollaboration,
}

abstract final class AppConstants {
  /// Build-time boundary: persisted preferences cannot enable upstream services.
  static const bool personalBuild = true;

  static const String githubRepoUrl =
      'https://github.com/dian-sen/XiaomiWatchfaceInstaller';
  static const String githubRepoApiUrl =
      'https://api.github.com/repos/dian-sen/XiaomiWatchfaceInstaller';
  static const String websiteUrl = githubRepoUrl;
  static const String androidCloudDownloadUrl = '$githubRepoUrl/releases';

  static const List<TeamMember> teamMembers = [
    TeamMember(
      name: 'OrPudding',
      role: TeamRole.mainDeveloperArchitecture,
      avatarAsset: 'assets/images/team/orpudding.jpg',
      githubUrl: 'https://github.com/orpudding',
    ),
    TeamMember(
      name: 'zxxhcj',
      role: TeamRole.zeppOSImplementation,
      avatarAsset: 'assets/images/team/zxxhcj.jpg',
      githubUrl: 'https://github.com/zxxhcj',
    ),
    TeamMember(
      name: 'Diana',
      role: TeamRole.harmonyOSPorting,
      avatarAsset: 'assets/images/team/diana9905454.jpg',
      githubUrl: 'https://github.com/diana9905454',
    ),
    TeamMember(
      name: 'IKUN_CXKPRO',
      role: TeamRole.deviceConnectionCollaboration,
      avatarAsset: 'assets/images/team/ikun_cxkpro.jpg',
      githubUrl: 'https://github.com/IKUN-CXKPRO',
    ),
  ];
}
