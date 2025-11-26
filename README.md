# Claude Code Usage IntelliJ Plugin

![Build](https://github.com/Luxusio/Claude-Code-Usage-Intellij/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

<!-- Plugin description -->
**Claude Code Usage** is an IntelliJ IDEA plugin that displays your Claude Code CLI usage statistics directly in your IDE.

## Features

- **Tool Window**: View detailed usage statistics including:
  - Today's token usage and cost
  - Monthly token usage and cost
  - Historical usage table with daily breakdown

- **Status Bar Widget**: Quick glance at today's token usage in the IDE status bar

- **Auto-refresh**: Usage data is automatically refreshed periodically

## Requirements

- [Claude Code CLI](https://docs.anthropic.com/claude/claude-code) must be installed and configured
- The `claude` command must be available in your system PATH

## How It Works

This plugin calls the `claude usage --output json` command to fetch your usage statistics from the Claude Code CLI.
<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Claude Code Usage"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

- Manually:

  Download the [latest release](https://github.com/Luxusio/Claude-Code-Usage-Intellij/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Usage

1. **Tool Window**: Click on "Claude Usage" in the right sidebar to open the detailed usage panel
2. **Status Bar**: Look at the bottom right of your IDE to see today's token usage
3. **Refresh**: Click the "Refresh" button in the tool window to manually update the data

## Screenshots

### Tool Window
The tool window shows:
- Today's usage (tokens and cost)
- Monthly usage (tokens and cost)
- Historical data in a table format

### Status Bar Widget
Shows a compact view of today's token usage (e.g., "12.5K tokens")

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
