# Parser

Parser/searcher for databases

## Usage

The config file is automaticalled generated. Enter links or file paths to the sources under the "bases" array.

Options in the config:
- "ignorecase" ignores capitalization when searching
- "contains" checks if the line contains the term instead of equaling it
- "crossreference" cross reference the found information
- "unwrap" remove unnecessary quotations or apostrophes  
- "bases" array of the links/paths to the sources

## Features

- Automatic config generation
- Supports most file types (JSON, CSV, TXT)
- Guesses the delimiter for txt/csv files
- Cross references found information
