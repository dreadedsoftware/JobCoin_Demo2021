# Scala Jobcoin
Akka based Jobcoin mixer application.

## Architecture
The Jobcoin Mixer is implimented as an Asynchronous Akka Actor application. Akka came pre-employed in the base project so, in the interest of time, Akka was used in the implementation.

The entry point is the JobcoinMixer class. This class initializes the ActorSystem and the MixerCreator Actor.

### JobcoinMixer
JobcoinMixer is implemented as a loop which takes as input a comma-separated list of addresses (Strings) and returns an address which is to be used by the Client as the deposit address into the mixing system. The new address is provided by the MixerCreator Actor.

### MixerCreator Actor
The MixerCreator Actor handles the ledger and mixing the deposit addresses to the addresses provided by the Client.

The ledger is a simple Map which maps deposit addresses to amounts held in the House Address which were transfered from the deposit address less the amounts transferred from the House Address into the addresses provided by the Client.

The MixerCreator uses the MixerNetwork Actor to perform all networking operations asynchronously. The number of Mixer Network Actors created is determined by configuration.

The MixerCreator Actor polls the network through the MixerNetwork Actor at a rate determined by configuration to find out whch Client addresses require mixing and which do not.

The amount of time it takes to disburse funds from the deposit addresses into the addresses supplied by the Client is dependent upon 1. The polling interval 2. The amount of funds to be mixed and 3. The number of mixing accounts provided by the client. The order in which mixing accounts provided by the client are deposited into is determined pseudorandomly using scala.util.Random.

### MixerNetwork Actor
The MixerNetwork Actor simply handles the network portion of the Application. The timeout for network.

### Configuration
baseUrl: The URL to be used for Jobcoin api calls
createUrl: The URL to be used for Jobcoin Creatin by the JobcoinClient class
apiBaseUrl: The URL used for api calls
apiAddressesUrl: The URL used for address api calls
apiTransactionsUrl: The URL used for transaction api calls
houseAddress: The house address to be mixed from
networkActors: The number of network Actors
defaultTimeout: The timeout used for network calls in ms
pollInterval: The polling interval in ms
transferFactor: Value used to determine how long mixing takes, larger values for larger mixing time
minimumTransfer: The minimum amount to transfer from the houseAddress to a mixing account
maxFee: The maximum fee charged
stateLocation: The filename of the file to save state into

## Tech Debt
There are some Tech Debt items incurred due to time contraints.

### Imperfect Ledger
The MixerCreator Actor is not a perfect ledger. There are a few corner cases which can exploite the system; although, a larger polling interval makes this increasingly unlikely. This can be resolved by creating a new MixerClient Actor which handles the ledger for each Client.

### Actor Recovery
There is no recovery mechanism in place for when a failure occurs during mixing.

### Client Ledger Lookup
There is no mechanism in place for a Client to check what balance they transferred into their deposit account and what balances they have in their mixing accounts. Thus, there is no way for a client to check what percentage fee the mixer is collecting.

### Unconstrained Precision
The Mixer uses BigDecimal for its ledger. This class is outstanding precision which can cause issues with RESTful services. This should be constrained to some configurable number of decimal places to bound the size of requests being made.

### Values in mixing accounts can diverge
The Mixer randomly orders the mixing accounts so, at infinity the mixing accounts should have received the same amount of Jobcoin. However, the real world does not exist at infinity so these values can diverge. A mechanism should be introduced to smooth these balances out.

### Run
`sbt run`


### Test
`sbt test`
