package com.example.zmkhelper;

public final class RepoConfig {
    public final String owner;
    public final String repo;
    public final String token;

    private RepoConfig(String owner, String repo, String token) {
        this.owner = owner;
        this.repo = repo;
        this.token = token == null ? "" : token.trim();
    }

    public static RepoConfig parse(String input, String token) {
        String value = input.trim();
        value = value.replace("https://github.com/", "");
        value = value.replace("http://github.com/", "");
        value = value.replace("git@github.com:", "");
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith(".git")) {
            value = value.substring(0, value.length() - 4);
        }
        String[] parts = value.split("/");
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException("GitHub repo must be owner/repo or a github.com URL");
        }
        return new RepoConfig(parts[0], parts[1], token);
    }

    public String slug() {
        return owner + "/" + repo;
    }
}
