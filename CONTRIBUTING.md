# Contributing

## How to contribute code to the project

 - Comment on the corresponding issue that you are working on it- so we
   don't get multiple developers working on the same thing
 - Follow the instructions at [How to Contribute to a GitHub
   Project](https://gist.github.com/MarcDiethelm/7303312) for instructions
   on how to make changes and then open a pull request
 - Include tests if possible!

## How to report a bug

 - Ensure that you're using the latest version of the app
 - Check if someone has already reported the bug
 - Provide the following information:
    - Detailed steps explaining how to reproduce the bug (if possible)
    - Android version and device name/variant
    - Any information about your server that you think might be relevant

## Building the app

 - Fork the repo
 - In Android Studio:
   - File -> New -> Project from Version Control
   - Enter url of your fork
   - Run app (green play button)

## Developer Notes

### Ktlint

Ktlint is a linting tool that is based on the [kotlin style guide](https://developer.android.com/kotlin/style-guide). It will validate and make sure that your code adheres to that style guide.

The [ktlint gradle plugin](https://github.com/jlleitschuh/ktlint-gradle) adds the ktlintCheck and ktlintFormat tasks to gradle.
- `ktlintCheck` - checks the source code for issues and outputs a report to `app/build/reports/ktlint`
- `ktlintFormat` - autoformats the code based on the kotlin style guide.

Ktlint check and format can be run on the code base by running the following commands in the root of the project.
```
./gradlew ktlintCheck
./gradlew ktlintFormat
```

### Git hook

A git hook has also been added. This basically runs the ktlintCheck every time a user tries to commit. If it finds violations it prompts the user to run ktlintFormat and fix any inconsistencies with the style guide. The purpose of this is to make sure that everyone is adhering to the style guide and writing clean code.

## Licensing

- All code contributions must be compatible with GPLv3
- Do not modify files listed in ASSETS-LICENSE unless you have explicit permission
- If contributing new icons or graphics, ensure they are either:
  - Original work you're contributing under GPLv3, OR
  - Properly licensed Material Design icons (Apache 2.0)

